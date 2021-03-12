package steam.boiler.core;

import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

//import com.sun.tools.javac.code.Attribute.Array;

import steam.boiler.model.PhysicalUnits;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.SteamBoilerCharacteristics;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;

public class MySteamBoilerController implements SteamBoilerController {

  /**
   * Captures the various modes in which the controller can operate.
   *
   * @author David J. Pearce
   *
   */
  private enum State {
        WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP
  }

  /**
   * Records the configuration characteristics for the given boiler problem.
   */
  private final SteamBoilerCharacteristics configuration;

  /**
   * Identifies the current mode in which the controller is operating.
   */
  private State mode = State.WAITING;
  
  /**
   * boolean that indicated is the emptying valve is open or closed
   */
  private Boolean emptyingTank = false;
  
  /**
   * doubles that store the last reading sent by the steam and water level sensors
   */
  private double lastKnownWaterLevel = 0.0;
  private double lastKnownSteamLevel = 0.0;
  
  /**
   * set of boolean arrays used to determine pump behavior, can be checked against incoming messages
   * to determine if pump or controller has malfunctioned
   */
  private Boolean[] pumpKnownState;
  private Boolean[] pumpStuckOpen;
  private Boolean[] pumpStuckClosed;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    int numberOfPumps = configuration.getNumberOfPumps();
    pumpKnownState = new Boolean[numberOfPumps];
    pumpStuckOpen = new Boolean[numberOfPumps];
    pumpStuckClosed = new Boolean[numberOfPumps];
    Arrays.fill(pumpKnownState, false);
    Arrays.fill(pumpStuckOpen, false);
    Arrays.fill(pumpStuckClosed, false);
  }

	/**
	 * This message is displayed in the simulation window, and enables a limited
	 * form of debug output. The content of the message has no material effect on
	 * the system, and can be whatever is desired. In principle, however, it should
	 * display a useful message indicating the current state of the controller.
	 *
	 * @return
	 */
	@Override
	public String getStatusMessage() {
		return mode.toString();
	}

	/**
	 * Process a clock signal which occurs every 5 seconds. This requires reading
	 * the set of incoming messages from the physical units and producing a set of
	 * output messages which are sent back to them.
	 *
	 * @param incoming The set of incoming messages from the physical units.
	 * @param outgoing Messages generated during the execution of this method should
	 *                 be written here.
	 */
	@Override
	public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
		// Extract expected messages
		Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
		Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
		// 
		Message boilerWaiting = extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming);
		Message physicalUnitReady = extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming);
		
		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		
		//Initialisation, check all components are "WAITING"
		if (this.mode == State.WAITING && boilerWaiting != null) {
			//checks Steam sensor is operational and no steam is being produced
			if(steamMessage == null || steamMessage.getDoubleParameter() != 0.0) {
				this.mode = State.EMERGENCY_STOP;
			} 
			//checks to see if level sensor is operational
			else if (levelMessage == null) {
				this.mode = State.DEGRADED;
			} 
			//all systems currently operational/waiting, proceed to initialisation mode
			else {
				initialisationMode(levelMessage.getDoubleParameter(),outgoing);
			}
		}
		
		//
		if(mode == State.READY) {
			outgoing.send(new Message(MessageKind.PROGRAM_READY));
			if(physicalUnitReady != null) {
				outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.NORMAL));
				this.mode = State.NORMAL;
			}
		}
		
		if(mode == State.NORMAL) {
			normalOperationMode(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter(), pumpStateMessages, pumpControlStateMessages, outgoing);
			Message failureMessage = detectFailureMessage(incoming);
			if (failureMessage != null) {
				outgoing.send(failureMessage);
				if(mode == State.DEGRADED) {
					degradeOperationalMode(failureMessage, pumpStateMessages, outgoing);
				} else if (mode == State.RESCUE) {
					rescueOperationalMode(failureMessage, pumpStateMessages, steamMessage.getDoubleParameter(), outgoing);
				}
			}
		}
		
		if(mode == State.EMERGENCY_STOP) {
			emergencyShutdown(outgoing);
		}
		
	}

	/**
	 * Check whether there was a transmission failure. This is indicated in several
	 * ways. Firstly, when one of the required messages is missing. Secondly, when
	 * the values returned in the messages are nonsensical.
	 *
	 * @param levelMessage      Extracted LEVEL_v message.
	 * @param steamMessage      Extracted STEAM_v message.
	 * @param pumpStates        Extracted PUMP_STATE_n_b messages.
	 * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
	 * @return
	 */
	private boolean transmissionFailure(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Message[] pumpControlStates) {
		// Check level readings
		if (levelMessage == null) {
			// Nonsense or missing level reading
			return true;
		} else if (steamMessage == null) {
			// Nonsense or missing steam reading
			return true;
		} else if (pumpStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump state readings
			return true;
		} else if (pumpControlStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump control state readings
			return true;
		}
		// Done
		return false;
	}

	/**
	 * Find and extract a message of a given kind in a mailbox. This must the only
	 * match in the mailbox, else <code>null</code> is returned.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The matching message, or <code>null</code> if there was not exactly
	 *         one match.
	 */
	private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
		Message match = null;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				if (match == null) {
					match = ith;
				} else {
					// This indicates that we matched more than one message of the given kind.
					return null;
				}
			}
		}
		return match;
	}

	/**
	 * Find and extract all messages of a given kind.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The array of matches, which can empty if there were none.
	 */
	private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
		int count = 0;
		// Count the number of matches
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				count = count + 1;
			}
		}
		// Now, construct resulting array
		Message[] matches = new Message[count];
		int index = 0;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				matches[index++] = ith;
			}
		}
		return matches;
	}
	
	/**
	 * Initialistion Mode turns on all the pumps, checking functionality, then closes pumps as the boiler\
	 * begins to fill, shuts all pumps when boiler is above optimal level (half capacity)
	 * 
	 * @param waterLevel - double indicating current water level reading given via sensor
	 * @param outgoing - outgoing mailbox to send pump controllers commands
	 */
	private void initialisationMode(Double waterLevel, @NonNull Mailbox outgoing) {
		
		//make sure emptying valve is closed before starting to fill tank
		if(emptyingTank) {
			outgoing.send(new Message(MessageKind.VALVE));
		}
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
		//turn on all pumps to allow for faster filling of boiler, also tests all pumps are operational
		if(waterLevel < minWaterLevel()) {
			for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
				outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
				pumpKnownState[i] = true;
			}
		} 
		//turns off pumps 2 and 3, once level is above minimal normal level
		if (waterLevel > minWaterLevel()) {
			for(int i = configuration.getNumberOfPumps()-1; i > 0; i--) {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
				pumpKnownState[i] = false;
			}
		} 
		//turns off pumps 0 and 1, once level is above optimal water level (half capacity)
		if (waterLevel >= optimalWaterLevel()){
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
			pumpKnownState[0] = false;
			//checks water level is within minimal and max normal water level
			if(waterLevel > minWaterLevel() && waterLevel < maxWaterLevel()) {
				this.mode = State.READY;
			} else if (waterLevel > maxWaterLevel()) {
				outgoing.send(new Message(MessageKind.VALVE));
				emptyingTank = true;
			}
		}
	}
	
	/**
	 * simple control method that changes pump status depending upon need, dictated by pumpsToActivate method
	 * 
	 * @param levelMessage - incoming message from the level sensor
	 * @param steamMessage - incoming message from steam sensor
	 * @param pumpStateMessages - incoming message about pumps states
	 * @param outgoing - send messages to pump controllers
	 */
	private void normalOperationMode(Double waterLevel, Double steamLevel, Message[] pumpStates, Message[] pumpControllerStates,@NonNull Mailbox outgoing) {
		//System.out.println("normal operation mode");
		int pumpsToActivate = pumpsToActivate(waterLevel, steamLevel);
		checkPumpState(pumpStates, pumpControllerStates);
		//updates the lastKnownWaterLevel each cycle, in case water level sensor failures
		if(waterLevel != null) {
			lastKnownWaterLevel = waterLevel;
		}
		
		if(steamLevel != null) {
			lastKnownSteamLevel = steamLevel;
		}
		
		if(detectAnomaly(waterLevel, steamLevel, pumpsToActivate) != 0) {
			mode = State.DEGRADED;
		}
		turnPumpsOnOff(getPumpsOpen(pumpStates), pumpsToActivate, outgoing);
		
	}
	
	private void rescueOperationalMode(Message failureMessage,Message[] pumpStates, Double steamLevel, @NonNull Mailbox outgoing) {
		int currentPumpsOpen = getPumpsOpen(pumpStates); //represents no. of pumps open, 0 would indicated 1 pump, pump no.0 is open

		lastKnownWaterLevel = estimateWaterLevel(currentPumpsOpen, lastKnownWaterLevel, steamLevel);
		
		int pumpsToActivate = pumpsToActivate(lastKnownWaterLevel, steamLevel);
		
		turnPumpsOnOff(currentPumpsOpen, pumpsToActivate, outgoing);
	}

	private void degradeOperationalMode(Message failureMessage, Message[] pumpStates, @NonNull Mailbox outgoing) {
		System.out.println("degraded mode");
		
		int waterLevelAnomaly = detectAnomaly(lastKnownWaterLevel, lastKnownSteamLevel,getPumpsOpen(pumpStates));
		
		//-1 suggests a pump is stuck closed
		if(waterLevelAnomaly == -1) {
			
		}
		//1 suggests a pumps is stuck open
		else if(waterLevelAnomaly == 1) {
			
		}
		
	}
	
	private void emergencyShutdown(@NonNull Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.STOP));
		if(!emptyingTank) {
			outgoing.send(new Message(MessageKind.VALVE));
			emptyingTank = true;
		}
	}
		
	/**
	 * Checks incoming Mailbox for failure detection messages, then returns acknowledgement message 
	 * 
	 * @param incoming - incoming Mailbox
	 * @return Message - new failure acknowledgement message
	 */
	private Message detectFailureMessage(@NonNull Mailbox incoming) {
		double waterLevel = extractOnlyMatch(MessageKind.LEVEL_v, incoming).getDoubleParameter();
		
		Message steamFailure = extractOnlyMatch(MessageKind.STEAM_FAILURE_DETECTION, incoming);
		Message levelFailure = extractOnlyMatch(MessageKind.LEVEL_FAILURE_DETECTION, incoming);
		Message[] pumpControlFailure = extractAllMatches(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n,incoming);
		Message[] pumpFailure = extractAllMatches(MessageKind.PUMP_FAILURE_DETECTION_n, incoming);
		
		if(levelFailure != null) {
			mode = State.RESCUE;
			return new Message(MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT);
		}
		
		if(steamFailure != null) {
			mode = State.DEGRADED;
			return new Message(MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT);
		}
		
		if(waterLevel > maxSafteyLimit() || waterLevel < minSafteyLimit()) {
			mode = State.EMERGENCY_STOP;
		}
		
		if(pumpControlFailure.length > 0) {
			for(int i = 0; i <= pumpControlFailure.length; i++) {
				if(pumpControlFailure[i] != null) {
					mode = State.DEGRADED;
					return new Message(MessageKind.PUMP_CONTROL_FAILURE_ACKNOWLEDGEMENT_n, i);
				}
			}
		}
		
		if(pumpFailure.length > 0) {
			for(int i = 0; i <= pumpFailure.length; i++) {
				if(pumpFailure[i] != null) {
					mode = State.DEGRADED;
					return new Message(MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n, i);
				}
			}
		}
		return null;
	}
	
	/**
	 * uses the current water level, plus pump capacity minus the steam produced to determine to min/max and average values per no of pumps open
	 * stores these value in an array of doubles. checks how many pumps need to be opened to reach the optimal level, whilst also making sure it 
	 * will not go over the max 
	 * 
	 * @param waterLevel - double representative of the water level given by the level sensor
	 * @param steamLevel - double representative of the stem production given by the steam sensor
	 * @return pumpsToActivate - integer, number of pumps to activate -1 indicates nil pumps to open
	 */
	private int pumpsToActivate(Double waterLevel, Double steamLevel) {
		int pumpsToActivate = -1;
		int pumpsAvaliable = getNoOfPumps();
		
		double waterDecepency = configuration.getCapacity();
		
		double[] proximtyToOptimal = {0,0,0,0};
		double[] maxPerPumpsOpen = {0,0,0,0};
		double[] minPerPumpsOpen = {0,0,0,0};
		
		//calculate the possible range of the water levels per pump(s) activated
		for(int i = 0; i < pumpsAvaliable; i++) {
			maxPerPumpsOpen[i] = getMaxLevelPerPump(waterLevel, i, steamLevel);
			minPerPumpsOpen[i] = getMinLevelPerPump(waterLevel, i);
			proximtyToOptimal[i] = Math.abs(((maxPerPumpsOpen[i] + minPerPumpsOpen[i]) / 2) - optimalWaterLevel());
		}
		//if current water level is above max normal level, returns -1 which will shut all pumps
		if(waterLevel >= maxWaterLevel()) {
			return -1;
		}

		//Determines how many pumps to activate based on possible ranges
		for(int i = 0; i < pumpsAvaliable;i++) {
			if(proximtyToOptimal[i] < waterDecepency && maxPerPumpsOpen[i] < maxWaterLevel()) {
				waterDecepency = proximtyToOptimal[i];
				pumpsToActivate = i;
			}
		}
		return pumpsToActivate;
	}
	
	/**
	 * returns an integer indicating if the Anomaly is positive or negative
	 * returning 1 means the water level is above the expected level + 10%
	 * returning -1 means the level is below the expected value - 10%
	 * returning 0 means the level is within the expected values
	 * 
	 * if the water level sensor is working and the water level is outside the 
	 * expected range, this indicates a failure in the pump controller
	 * 
	 * @param waterLevel
	 * @param steamLevel
	 * @param pumpsActivated
	 * @return integer
	 */
	private int detectAnomaly(double waterLevel, double steamLevel, int pumpsActivated) {
		double maxLevel = getMaxLevelPerPump(waterLevel, pumpsActivated, steamLevel) * 1.1;
		double minLevel = getMinLevelPerPump(waterLevel, pumpsActivated) * 0.9;
		if(waterLevel > maxLevel && waterLevel > maxWaterLevel()) {
			return 1;
		} else if (waterLevel < minLevel && waterLevel < minWaterLevel()) {
			return -1;
		}
		return 0;
	}
	
	/**
	 * checks the known state of the pump stored in the pumpKnownState boolean array
	 * if the boolean array is different to the message received from the controller
	 * this indicates that the pump has malfunctioned
	 * 
	 * @param pumpStates - Messages sent by the physical unit
	 * @return integer   - indicates which pump has malfunctioned
	 */
	private void checkPumpState(Message[] pumpStates, Message[] pumpControllerStates) {
		//System.out.println("--------------------------------------------------------");
		for(int i = 0; i < getNoOfPumps(); i++) {
			if(getPumpState(pumpStates, i) != pumpKnownState[i]) {
				if(getPumpState(pumpStates, i) == false) {
					pumpStuckOpen[i] = true;
				} 
				else if (getPumpState(pumpStates, i) == true) {
					pumpStuckClosed[i] = true;
				}
			}
			//System.out.println("Pump Stuck open boolean: " + i + "-" + pumpStuckOpen[i]);
			//System.out.println("Pump Stuck Closed boolean: " + i + "-" + pumpStuckClosed[i]);
		}
		//System.out.println("--------------------------------------------------------");
	}
	
	/**
	 * Helper method used to return true capacity of pumps, useful if not all pumps have the 
	 * same capacity. i.e. half capacity etc
	 * 
	 * @param pumpNumber - which/how many of the pumps capacity is required
	 * @return pumpTotalCapacity - a double dictating the sum of the selected pumps capacity
	 */
	private double getPumpTotalCapacity(int numberOfPumps) {
		int pumpTotalCapacity = 0;
		int pumpNoStuckClosed = -1;
		int pumpNoStuckOpen = -1;
		
		for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
			if(pumpStuckClosed[i] == true) {
				pumpNoStuckClosed = i;
				System.out.println("Pump closed " + pumpNoStuckClosed);
			}
			if(pumpStuckOpen[i] == true) {
				pumpNoStuckOpen = i;
				System.out.println("Pump open " + pumpNoStuckOpen);
			}
		}
		
		for(int i = 0; i <= numberOfPumps; i++) {
			pumpTotalCapacity += configuration.getPumpCapacity(i);
			if(pumpNoStuckOpen > i && pumpNoStuckOpen != i) {
				pumpTotalCapacity += configuration.getPumpCapacity(pumpNoStuckOpen);
			}
			else if(pumpNoStuckClosed <= i && pumpNoStuckClosed != -1) {
				pumpTotalCapacity -= configuration.getPumpCapacity(pumpNoStuckClosed);
			}
		}
		System.out.println(pumpTotalCapacity);
		return pumpTotalCapacity;
	}
	
	/**
	 * Returns an integer indicating the amount of pumps open
	 * 
	 * @param pumpStates
	 * @return integer 
	 */
	private int getPumpsOpen(Message[] pumpState) {
		int currentPumpsOpen = -1;
		int pumpsAvailable = configuration.getNumberOfPumps();
		for(int i = 0; i < pumpsAvailable; i++) {
			if(getPumpState(pumpState, i) == true) {
				currentPumpsOpen += 1;
			}
		}
		return currentPumpsOpen;
	}
	
	/**
	 * sends messages to turn pumps on or off depending on need
	 * 
	 * @param currentPumpsOpen
	 * @param pumpsToActivate
	 * @param outgoing
	 */
	private void turnPumpsOnOff(int currentPumpsOpen, int pumpsToActivate, @NonNull Mailbox outgoing) {
		int pumpsAvailable = configuration.getNumberOfPumps();
		if(currentPumpsOpen > pumpsToActivate) {
			for(int i = (pumpsAvailable - 1); i > pumpsToActivate; i--) {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
				pumpKnownState[i] = false;

			}
		} else if (currentPumpsOpen < pumpsToActivate) {
			for(int i = 0; i <= pumpsToActivate; i++) {
				outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
				pumpKnownState[i] = true;

			}
		}
	}
	
	/**
	 * method used to estimate water level if the sensor breaks based of last known reading
	 * for first cycle it will use the last reading provided by the sensor
	 * after first cycle it will use the max per pump opened as an estimated of the water level
	 * 
	 * @param pumpsActive
	 * @param waterLevel
	 * @param steamLevel
	 * @return an estimated water level in lieu of the water level sensor reading
	 */
	private double estimateWaterLevel(int pumpsActive, Double waterLevel, Double steamLevel) {
		double maxPossibleWaterLevel = getMaxLevelPerPump(waterLevel, pumpsActive, steamLevel);
		double minPossibleWaterLevel = getMinLevelPerPump(waterLevel, pumpsActive);
		return ((maxPossibleWaterLevel + minPossibleWaterLevel) / 2);
	}
	
	private double estimateSteamLevel(double lastKnownWaterLevel, double waterLevel, int pumpsOpen) {
		return (lastKnownWaterLevel + (getPumpTotalCapacity(pumpsOpen) * 5)) - waterLevel;
	}
	
	/**
	 * helper method used to calculate the max possible water level after one cycle
	 * 
	 * @param waterLevel  - current water level, either by sensor reading or estimateWaterLevel method
	 * @param pumpsActive - integer indicating how many pumps will be opened
	 * @param steamLevel  - current steam output level given via sensor
	 * @return double     - max possible water level after one cycle
	 */
	private double getMaxLevelPerPump(double waterLevel, int pumpsActive, double steamLevel) {
		return waterLevel + (5 * getPumpTotalCapacity(pumpsActive)) - (5 * steamLevel);
	}
	
	/**
	 * helper method used to calculate the minimum possible water level after one cycle
	 * 
	 * @param waterLevel  - current water level, either by sensor reading or estimateWaterLevel method
	 * @param pumpsActive - integer indicating how many pumps will be opened
	 * @return double     - minimum possible water level after one cycle
	 */
	private double getMinLevelPerPump(double waterLevel, int pumpsActive) {
		return waterLevel + (5 * getPumpTotalCapacity(pumpsActive)) - (5 * configuration.getMaximualSteamRate());
	}
	
	/**
	 * helper method to return true if pump controller has opened pump
	 * 
	 * @param pumpControllerState - pump Controller Messages
	 * @param pumpNo              - integer indicating the pump number 
	 * @return boolean            - true, pump open, false pump closed
	 */
	private Boolean getPumpControllerState(Message[] pumpControllerState,int pumpNo) {
		return pumpControllerState[pumpNo].getBooleanParameter();
	}
	
	/**
	 * helper Method to return if true if the pump indicates its open
	 * 
	 * @param pumpState - Messages sent by pump
	 * @param pumpNo    - integer indicating the pump number
	 * @return boolean  - true if pump is open, false if pump is closed
	 */
	private Boolean getPumpState(Message[] pumpState,int pumpNo) {
		return pumpState[pumpNo].getBooleanParameter();
	}
	
	private int getNoOfPumps() {
		return configuration.getNumberOfPumps();
	}
	
	/**
	 * helper method, returns the minimal normal level
	 * 
	 * @return Double - minimal normal water level
	 */
	private Double minWaterLevel() {
		return configuration.getMinimalNormalLevel();
	}
	
	/**
	 * helper method, returns the maximal normal level
	 * 
	 * @return Double - maximal normal water level
	 */
	private Double maxWaterLevel() {
		return configuration.getMaximalNormalLevel();
	}
	
	/**
	 * helper method, returns the optimal water level
	 * max water level plus minimal water level divided by two
	 * 
	 * @return Double - optimal water level
	 */
	private Double optimalWaterLevel() {
		return (configuration.getMaximalNormalLevel() + configuration.getMinimalNormalLevel())/2;
	}
	
	/**
	 * helper method, returns the maximum safe water level
	 * 
	 * @return Double - max water level
	 */
	private Double maxSafteyLimit() {
		return configuration.getMaximalLimitLevel();
	}
	
	/**
	 * helper method, returns the minimum safe water level
	 * 
	 * @return Double - minimum water level
	 */
	private Double minSafteyLimit() {
		return configuration.getMinimalLimitLevel();
	}
	
}

