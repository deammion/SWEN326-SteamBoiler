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
  private boolean emptyingTank = false;
  
  /**
   * doubles that store the last reading sent by the steam and water level sensors
   */
  private double lastKnownWaterLevel = 0.0;
  private double lastKnownSteamLevel = 0.0;
  
  /**
   * doubles to store the max water level per pumps active
   */
  private double maxPossibleWaterLevel = 500.0;
  private double minPossibleWaterLevel = 0.0;
  
  /**
   * set of boolean arrays used to determine pump behavior, can be checked against incoming messages
   * to determine if pump or controller has malfunctioned
   */
  private boolean[] pumpKnownState;
  private boolean[] pumpFailureDetected;
  private boolean[] pumpControllerFailureDetected;
  
  /**
   * boolean to track steam level and water level sensor failures
   */
  private boolean steamLevelFailure = false;
  private boolean waterLevelFailure = false;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    int numberOfPumps = configuration.getNumberOfPumps();
    pumpKnownState = new boolean[numberOfPumps];
    pumpFailureDetected = new boolean[numberOfPumps];
    pumpControllerFailureDetected = new boolean[numberOfPumps];
    Arrays.fill(pumpKnownState, false);
    Arrays.fill(pumpFailureDetected, false);
    Arrays.fill(pumpControllerFailureDetected, false);
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
		//extract waiting message, and physical unit ready message
		Message boilerWaiting = extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming);
		Message physicalUnitReady = extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming);
		
		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		
		if(detectPumpFailure(levelMessage.getDoubleParameter(), pumpStateMessages, pumpControlStateMessages, outgoing) || 
				detectSteamLevelFailure(steamMessage, outgoing)) {
			mode = State.DEGRADED;
		}
		
		if(dectectWaterLevelFailure(levelMessage, outgoing) && mode == State.NORMAL) {
			this.mode = State.RESCUE;
		}
				
		//Initialisation, checks the boiler is "WAITING"
		if (this.mode == State.WAITING && boilerWaiting != null) {
			//checks Steam sensor is operational and no steam is being produced
			if(steamMessage == null || steamMessage.getDoubleParameter() != 0.0) {
				this.mode = State.EMERGENCY_STOP;
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
		}
		
		if(mode == State.DEGRADED) {
			degradeOperationalMode(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter(), pumpStateMessages, outgoing);
		}
		
		if(mode == State.RESCUE) {
			rescueOperationalMode(pumpStateMessages, null, outgoing);
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

		//updates the lastKnownWaterLevel each cycle, in case the water level sensor fails
		if(waterLevel != null) {
			lastKnownWaterLevel = waterLevel;
		}
		//updates the lastKnownSteamLevel each cycle, in case the steam level sensor fails
		if(steamLevel != null) {
			lastKnownSteamLevel = steamLevel;
		}
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
		
	}
	
	/**
	 * control method for operating in a degraded state, if steam level sensor has failed
	 * the method will estimate the steam level
	 * else system will operate as normal, as turnPumpsOnOff can account for pump/controller failure 
	 * 
	 * @param waterLevel
	 * @param steamLevel
	 * @param pumpStates
	 * @param outgoing
	 */
	private void degradeOperationalMode(Double waterLevel, Double steamLevel, Message[] pumpStates, @NonNull Mailbox outgoing) {
		//if failure is degraded mode is due to steam sensor failure, system will estimate the steam level
		if(steamLevelFailure) {
			steamLevel = estimateSteamLevel(lastKnownWaterLevel, waterLevel, getPumpsOpen(pumpStates));
		}
		
		int pumpsToActivate = pumpsToActivate(waterLevel, steamLevel);
		
		if(waterLevel != null) {
			lastKnownWaterLevel = waterLevel;
		}
		
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
	}
	
	/**
	 * control method for operation in rescue mode, uses an algorithm to estimate the water level
	 * for the first cycle, it uses the last known water level given by the operational level sensor
	 * after that the lastKnownWaterLevel is updated to the estimated level
	 * 
	 * @param pumpStates
	 * @param steamLevel
	 * @param outgoing
	 */
	private void rescueOperationalMode(Message[] pumpStates, Double steamLevel, @NonNull Mailbox outgoing) {
		int activePumps = getPumpsOpen(pumpStates).length;
		double estimatedWaterLevel = estimateWaterLevel(activePumps, lastKnownWaterLevel, steamLevel);
		
		int pumpsToActivate = pumpsToActivate(estimatedWaterLevel, steamLevel);
		
		lastKnownWaterLevel = estimatedWaterLevel;
		
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
	}
	
	/**
	 * emergency shutdown, sends a stop message, then starts emptying the tank
	 * 
	 * @param outgoing - outgoing mailbox to send stop and valve opening message
	 */
	private void emergencyShutdown(@NonNull Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.STOP));
		if(!emptyingTank) {
			outgoing.send(new Message(MessageKind.VALVE));
			emptyingTank = true;
		}
	}
				
	/**
	 * checks pump states given by the pump itself and controller against the know state held by the program
	 * to determine if a failure has occurred
	 * 
	 * @param waterLevel
	 * @param pumpStates
	 * @param pumpControllerStates
	 * @param outgoing
	 */
	private boolean detectPumpFailure(Double waterLevel, Message[] pumpStates, Message[] pumpControllerStates, Mailbox outgoing) {
		for(int i = 0; i < getNoOfPumps(); i++) {
			//FAIL - c3 -controller
			if(getPumpState(pumpStates, i) == pumpKnownState[i] 
					&& getPumpControllerState(pumpControllerStates, i) != pumpKnownState[i]
							&& waterLevelWithinLimits(waterLevel)) {
					outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n,i));
					pumpControllerFailureDetected[i] = true;
					return true;
			}
			//FAIL - c4 -pump
			else if (getPumpState(pumpStates, i) == pumpKnownState[i] 
					&& getPumpControllerState(pumpControllerStates, i) != pumpKnownState[i]
							&& !waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				pumpFailureDetected[i] = true;
				return true;
				
			}
			//FAIL - c5 -pump
			else if (getPumpState(pumpStates, i) != pumpKnownState[i] 
					&& getPumpControllerState(pumpControllerStates, i) == pumpKnownState[i]
							&& waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				pumpFailureDetected[i] = true;
				return true;
			}
			//FAIL - c6 -pump
			else if (getPumpState(pumpStates, i) != pumpKnownState[i] 
					&& getPumpControllerState(pumpControllerStates, i) == pumpKnownState[i]
							&& !waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				pumpFailureDetected[i] = true;
				return true;
			}
			//FAIL - c7 -pump
			else if (getPumpState(pumpStates, i) != pumpKnownState[i] 
					&& getPumpControllerState(pumpControllerStates, i) != pumpKnownState[i]
							&& waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				pumpFailureDetected[i] = true;
				return true;
			}
			//FAIL - c8 - pump
			else if (getPumpState(pumpStates, i) != pumpKnownState[i] 
					&& getPumpControllerState(pumpControllerStates, i) != pumpKnownState[i]
							&& !waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				pumpFailureDetected[i] = true;
				return true;
			}
		}
		return false;
	}
	
	/**
	 * detects if the steam level sensor is outside of the limits
	 * 
	 * @param steamLevel - double given by the steam level sensor
	 * @param outgoing   - outgoing mailbox to send failure detection message
	 */
	private boolean detectSteamLevelFailure(Message steamLevel, Mailbox outgoing) {
		if(steamLevel.getDoubleParameter() < 0.0 || steamLevel.getDoubleParameter() > configuration.getMaximualSteamRate()) {
			steamLevelFailure = true;
			outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
			return true;
		}
		return false;
	}
	
	/**
	 * detects if the water level sensor has failed, initially checks issue is not caused 
	 * by pump or pump controller failure
	 * 
	 * @param waterLevel - incoming water level sent by the sensor
	 * @param outgoing   - outgoing mailbox to send level sensor failure detection message
	 * @return boolean   - true indicates level sensor failure
	 */
	private boolean dectectWaterLevelFailure(Message waterLevel, Mailbox outgoing) {
		boolean pumpOrPumpControllerFailure = false;
		
		//checks for failure of pump or controller
		for(int i = 0; i < getNoOfPumps(); i++) {
			if(pumpFailureDetected[i] == true || pumpControllerFailureDetected[i] == true) {
				pumpOrPumpControllerFailure = true;
			}
		}
		
		//checks for possible issues cause by level sensor failure
		//checks level reading is not outside possible ranges
		if(waterLevel.getDoubleParameter() < 0.0 || waterLevel.getDoubleParameter() > configuration.getCapacity()) {
			waterLevelFailure = true;
			outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			return true;
		} 
		//checks water level is within expected range and not caused by pump or controller failure
		else if (!waterLevelWithinLimits(waterLevel.getDoubleParameter()) && !pumpOrPumpControllerFailure) {
			waterLevelFailure = true;
			outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			return true;
		}
		return false;
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
				maxPossibleWaterLevel = maxPerPumpsOpen[i];
				minPossibleWaterLevel = minPerPumpsOpen[i];
			}
		}
		return pumpsToActivate;
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
		
		for(int i = 0; i <= numberOfPumps; i++) {
			pumpTotalCapacity += configuration.getPumpCapacity(i);
		}
		
		return pumpTotalCapacity;
	}
	
	/**
	 * Returns an integer indicating the amount of pumps open
	 * 
	 * @param pumpStates
	 * @return integer 
	 */
	private int[] getPumpsOpen(Message[] pumpState) {
		int[] currentPumpsOpen = Arrays.stream(pumpState).filter(m -> m.getBooleanParameter()).mapToInt(m -> m.getIntegerParameter()).toArray();
		return currentPumpsOpen;
	}
	
	/**
	 * Returns an integer indicating the amount of pumps open
	 * 
	 * @param pumpStates
	 * @return integer 
	 */
	private int[] getPumpsClosed(Message[] pumpState) {
		int[] currentPumpsClosed = Arrays.stream(pumpState).filter(m -> !m.getBooleanParameter()).mapToInt(m -> m.getIntegerParameter()).toArray();
		return currentPumpsClosed;
	}
	
	/**
	 * sends messages to turn pumps on or off depending on need
	 * 
	 * @param currentPumpsOpen
	 * @param pumpsToActivate
	 * @param outgoing
	 */
	private void turnPumpsOnOff(int[] openPumpArray, int[] closedPumpArray, int pumpsToActivate, @NonNull Mailbox outgoing) {
		int currentPumpsOpen = openPumpArray.length;
		int currentPumpsClosed = closedPumpArray.length;
		
		if(currentPumpsOpen > pumpsToActivate) {
			for(int i = currentPumpsOpen-1; i >= 0; i--) {
				int pumpNo = openPumpArray[i];
				if(currentPumpsOpen > pumpsToActivate && !pumpFailureDetected[pumpNo]) {
					outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, pumpNo));
					pumpKnownState[pumpNo] = false;
					currentPumpsOpen--;
				} else {
					outgoing.send(new Message(MessageKind.OPEN_PUMP_n, pumpNo));
					pumpKnownState[pumpNo] = true;
				}
			}
		}
		else {
			for(int i = 0; i < currentPumpsClosed; i++) {
				int pumpNo = closedPumpArray[i];
				if (currentPumpsOpen < pumpsToActivate && !pumpFailureDetected[pumpNo]) {
					outgoing.send(new Message(MessageKind.OPEN_PUMP_n, pumpNo));
					pumpKnownState[pumpNo] = true;
					pumpsToActivate--;
				} else {
					outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, pumpNo));
					pumpKnownState[pumpNo] = false;
				}
			}
		}
	}
	
	/**
	 * method used to estimate water level if the sensor breaks based of last known reading
	 * for first cycle it will use the last reading provided by the sensor
	 * after first cycle it will use the max per pump opened as an estimated of the water level
	 * 
	 * @param pumpsActive - pumps active used to get incoming water
	 * @param waterLevel  - water level see above
	 * @param steamLevel  - steam level reading sent by steam sensor 
	 * @return double     - an estimated water level in lieu of the water level sensor reading
	 */
	private double estimateWaterLevel(int pumpsActive, double waterLevel, double steamLevel) {
		double maxPossibleWaterLevel = getMaxLevelPerPump(waterLevel, pumpsActive, steamLevel);
		double minPossibleWaterLevel = getMinLevelPerPump(waterLevel, pumpsActive);
		return ((maxPossibleWaterLevel + minPossibleWaterLevel) / 2);
	}
	
	/**
	 * method used to estimate steam level production, when steam level fails
	 * 
	 * @param lastKnownWaterLevel - water level of the last cycle
	 * @param waterLevel          - water level of current cycle
	 * @param pumpsOpen			  - number of pumps open on current cycle
	 * @return double             - estimation of steam production
	 */
	private double estimateSteamLevel(double lastKnownWaterLevel, double waterLevel, int[] pumpsOpen) {
		double openPumpCapacity = 0.0;
		for(int i = 0; i < pumpsOpen.length; i++) {
			openPumpCapacity += configuration.getPumpCapacity(pumpsOpen[i]);
		}
		return Math.abs((lastKnownWaterLevel + (openPumpCapacity * 5)) - waterLevel);
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
	private boolean getPumpControllerState(Message[] pumpControllerState,int pumpNo) {
		return pumpControllerState[pumpNo].getBooleanParameter();
	}
	
	/**
	 * helper Method to return if true if the pump indicates its open
	 * 
	 * @param pumpState - Messages sent by pump
	 * @param pumpNo    - integer indicating the pump number
	 * @return boolean  - true if pump is open, false if pump is closed
	 */
	private boolean getPumpState(Message[] pumpState,int pumpNo) {
		return pumpState[pumpNo].getBooleanParameter();
	}
	
	/**
	 * 
	 * 
	 * @param waterLevel
	 * @return
	 */
	private boolean waterLevelWithinLimits(double waterLevel) {
		if(waterLevel > (minPossibleWaterLevel * 0.9) && waterLevel < (maxPossibleWaterLevel * 1.1)) {
			return true;
		}
		return false;
	}
	
	/**
	 * helper method, returns number of pumps in configuration
	 * 
	 * @return integer - number of pumps in the configuration
	 */
	private int getNoOfPumps() {
		return configuration.getNumberOfPumps();
	}
	
	/**
	 * helper method, returns the minimal normal level
	 * 
	 * @return double - minimal normal water level
	 */
	private double minWaterLevel() {
		return configuration.getMinimalNormalLevel();
	}
	
	/**
	 * helper method, returns the maximal normal level
	 * 
	 * @return double - maximal normal water level
	 */
	private double maxWaterLevel() {
		return configuration.getMaximalNormalLevel();
	}
	
	/**
	 * helper method, returns the optimal water level
	 * max water level plus minimal water level divided by two
	 * 
	 * @return double - optimal water level
	 */
	private double optimalWaterLevel() {
		return (configuration.getMaximalNormalLevel() + configuration.getMinimalNormalLevel())/2;
	}
	
	/**
	 * helper method, returns the maximum safe water level
	 * 
	 * @return double - max water level
	 */
	private double maxSafteyLimit() {
		return configuration.getMaximalLimitLevel();
	}
	
	/**
	 * helper method, returns the minimum safe water level
	 * 
	 * @return double - minimum water level
	 */
	private double minSafteyLimit() {
		return configuration.getMinimalLimitLevel();
	}
	
}

