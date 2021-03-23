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
  private double maxPossibleWaterLevel; //initialised in MySteamBoilerController
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
   * boolean to track steam boiler heater operation
   */
  private boolean heaterOn = false;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    int numberOfPumps = configuration.getNumberOfPumps();
    maxPossibleWaterLevel = configuration.getCapacity();
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
		
		if(this.mode != State.EMERGENCY_STOP) {
			if(detectRepair(incoming, outgoing)) {
				outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.NORMAL));
				this.mode = State.NORMAL;
			}
			
			if((detectPumpFailure(levelMessage.getDoubleParameter(), pumpStateMessages, pumpControlStateMessages, outgoing) || 
					detectSteamLevelFailure(steamMessage.getDoubleParameter(), outgoing)) && this.mode == State.NORMAL) {
				mode = State.DEGRADED;
			}
		
			if(dectectWaterLevelFailure(levelMessage, outgoing) && this.mode == State.NORMAL) {
				this.mode = State.RESCUE;
			}
			
			if(detectImminentFailure(lastKnownWaterLevel)) {
				closeAllPumps(outgoing);
				emergencyShutdown(outgoing);
				this.mode = State.EMERGENCY_STOP;
			}
		
			if (this.mode == State.WAITING) {
				waitingMode(steamMessage, levelMessage, boilerWaiting, pumpStateMessages, outgoing);
			}
		
			if(mode == State.READY) {
				readyMode(physicalUnitReady, outgoing);
			}
		
			if(mode == State.NORMAL) {
				normalOperationMode(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter(), pumpStateMessages, pumpControlStateMessages, outgoing);
			}
		
			if(mode == State.DEGRADED) {
				degradeOperationalMode(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter(), pumpStateMessages, outgoing);
			}
		
			if(mode == State.RESCUE) {
				rescueOperationalMode(pumpStateMessages, steamMessage.getDoubleParameter(), outgoing);
			}
			
			updateExpectedLevels(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter());
		}else if(mode == State.EMERGENCY_STOP) {
			closeAllPumps(outgoing);
			emergencyShutdown(outgoing);
		}
	}
	
	/**
	 * updates the lastKnownWaterLevel and lastKnownSteamLevel provided the sensor is still operating
	 * 
	 * @param waterLevel - Double parameter received from the level sensor
	 * @param steamLevel - Double parameter received from the steam sensor
	 */
	private void updateExpectedLevels(double waterLevel, double steamLevel) {
		if(!waterLevelFailure) {
			lastKnownWaterLevel = waterLevel;
		}
		if(!steamLevelFailure) {
			lastKnownSteamLevel = steamLevel;
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
	 *  waiting mode, checks for failure of any critical system such as water level sensor or steam level failure
	 *  asserts the boiler is in a waiting state. then begins to fill the tank via initialisationMode()
	 * 
	 * @param steamMessage      - Message received from the steam level sensor
	 * @param levelMessage      - Message received from the water level sensor
	 * @param boilerWaiting     - Message containing the boiler waiting message, if it exists
	 * @param pumpStateMessages - Messages received from the pumps
	 * @param outgoing          - Mailbox to sent require messages
	 */
	private void waitingMode(Message steamMessage, Message levelMessage, Message boilerWaiting, Message[] pumpStateMessages, Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
		//checks for possible failures that could be safety critical
		if(steamMessage == null || steamMessage.getDoubleParameter() != 0.0
				|| waterLevelFailure || steamLevelFailure) {
			closeAllPumps(outgoing);
			emergencyShutdown(outgoing);
		} 
		//asserts boiler is waiting i.e. not operating
		if(boilerWaiting != null) {
			initialisationMode(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter(), pumpStateMessages, outgoing);
		}
	}
	
	/**
	 * ready mode, send to the physical units that the program is ready to begin operating
	 * waits to receive a message from the physical units that they are ready to begin
	 * sets mode to normal, thus normal operation can begin via normalOperationMode()
	 * 
	 * @param physicalUnitReady - Message stating the physical units are ready to begin operating
	 * @param outgoing          - Mailbox to send required messages
	 */
	private void readyMode(Message physicalUnitReady, Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.PROGRAM_READY));
		//checks physical units are ready
		if(physicalUnitReady != null) {
			heaterOn = true;
			outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.NORMAL));
			this.mode = State.NORMAL;
		} 
	}
	
	/**
	 * Initialistion Mode turns on all the pumps, checking functionality, then closes pumps as the boiler\
	 * begins to fill, shuts all pumps when boiler is above optimal level (half capacity)
	 * 
	 * @param waterLevel - Double indicating current water level reading given via sensor
	 * @param outgoing   - Outgoing mailbox to send pump controllers commands
	 */
	private void initialisationMode(double waterLevel, double steamLevel, Message[] pumpStates, @NonNull Mailbox outgoing) {
		//make sure emptying valve is closed before starting to fill tank
		if(emptyingTank && waterLevel < maxWaterLevel()) {
			outgoing.send(new Message(MessageKind.VALVE));
			emptyingTank = false;
		}
		
		//begin filling tank
		int pumpsToActivate = pumpsToActivate(waterLevel, steamLevel);
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);

		//checks water level is within minimal and max normal water level
		if(waterLevel > minWaterLevel() && waterLevel < maxWaterLevel()) {
				this.mode = State.READY;
		} else if (waterLevel > maxWaterLevel()) {
				outgoing.send(new Message(MessageKind.VALVE));
				emptyingTank = true;
		}		
	}
	
	/**
	 * simple control method used to maintain water level during normal operating conditions
	 * 
	 * @param waterLevel           - Double parameter received from the level sensor
	 * @param steamLevel           - Double parameter received from the steam sensor
	 * @param pumpStates           - Messages received from the pumps
	 * @param pumpControllerStates - Messages received from the pump controllers
	 * @param outgoing             - Mailbox to send required messages
	 */
	private void normalOperationMode(Double waterLevel, Double steamLevel, Message[] pumpStates, Message[] pumpControllerStates,@NonNull Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.NORMAL));
		int pumpsToActivate = pumpsToActivate(waterLevel, steamLevel);
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);		
	}
	
	/**
	 * control method for operating in a degraded state, if steam level sensor has failed
	 * the method will estimate the steam level
	 * else system will operate as normal, as turnPumpsOnOff can account for pump/controller failure 
	 * 
	 * @param waterLevel - Double parameter received from the level sensor
	 * @param steamLevel - Double parameter received from the steam sensor
	 * @param pumpStates - Messages received from the pumps
	 * @param outgoing   - Mailbox to send required messages
	 */
	private void degradeOperationalMode(Double waterLevel, Double steamLevel, Message[] pumpStates, @NonNull Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
		//if degraded mode is due to steam sensor failure, system will estimate the steam level
		if(steamLevelFailure) {
			steamLevel = estimateSteamLevel(lastKnownWaterLevel, waterLevel, getPumpsOpen(pumpStates));
		}
		int pumpsToActivate = pumpsToActivate(waterLevel, steamLevel);
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
	}
	
	/**
	 * control method for operation in rescue mode, uses an algorithm to estimate the water level
	 * for the first cycle, it uses the last known water level given by the operational level sensor
	 * after that the lastKnownWaterLevel is updated to the estimated level
	 * 
	 * @param pumpStates - Messages received from the pumps
	 * @param steamLevel - Double parameter received from the steam sensor
	 * @param outgoing   - Mailbox to send required messages
	 */
	private void rescueOperationalMode(Message[] pumpStates, Double steamLevel, @NonNull Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
		int activePumps = getPumpsOpen(pumpStates).length;
		//Estimate water level since sensor failure has been detected
		double estimatedWaterLevel = estimateWaterLevel(activePumps, lastKnownWaterLevel, steamLevel);
		//Operate as normal using estimated water level
		int pumpsToActivate = pumpsToActivate(estimatedWaterLevel, steamLevel);
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
		//update lastKnownWaterLevel to be used in next cycle
		lastKnownWaterLevel = estimatedWaterLevel;
	}
	
	/**
	 * emergency shutdown, sends a stop message, then starts emptying the tank
	 * 
	 * @param outgoing - Outgoing mailbox to send stop and valve opening message
	 */
	private void emergencyShutdown(@NonNull Mailbox outgoing) {
		//send emergency stop message three times
		for(int i =0; i <= 2; i++) {
			outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.EMERGENCY_STOP));
		}
		//begin emptying tank
		if(!emptyingTank) {
			outgoing.send(new Message(MessageKind.VALVE));
			emptyingTank = true;
		}
	}
				
	/**
	 * checks pump states given by the pump itself and controller against the know state held by the program
	 * also confirms if the water level is within expected limits to determine if a failure has occurred
	 * 
	 * @param waterLevel           - Double parameter received from the level sensor
	 * @param pumpStates           - Messages received from the pumps
	 * @param pumpControllerStates - Messages received from the pump controllers
	 * @param outgoing             - Mailbox to send required messages
	 */
	private boolean detectPumpFailure(Double waterLevel, Message[] pumpStates, Message[] pumpControllerStates,@NonNull Mailbox outgoing) {
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
	 * @param steamLevel - Double given by the steam level sensor
	 * @param outgoing   - Outgoing mailbox to send failure detection message
	 * @return boolean   - True indicates steam sensor failure
	 */
	private boolean detectSteamLevelFailure(double steamLevel, Mailbox outgoing) {
		if(steamLevel < 0.0 || steamLevel > configuration.getMaximualSteamRate()
				|| steamLevel < lastKnownSteamLevel) {
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
	 * @param waterLevel - Incoming water level sent by the sensor
	 * @param outgoing   - Outgoing mailbox to send level sensor failure detection message
	 * @return boolean   - True indicates level sensor failure
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
		
		//checks water level is outside expected range and not caused by pump or controller failure
		//asserts boiler is operating, else false positive will be triggered during initialisationMode()
		else if (!waterLevelWithinLimits(waterLevel.getDoubleParameter()) && !pumpOrPumpControllerFailure && 
				(mode == State.NORMAL || mode == State.DEGRADED)) {
			waterLevelFailure = true;
			outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			return true;
		}
		return false;
	}
	
	/**
	 * detects if failures which can result in critical failure has occurred 
	 * which will trigger an emergency shut down
	 * 
	 * @param waterLevel - Double parameter received by water level sensor
	 * @return boolean   - True means multiply failures have been detected
	 */
	private boolean detectImminentFailure(double waterLevel) {
		if(waterLevelFailure && steamLevelFailure) {
			return true;
		} else if (waterLevel > maxSafteyLimit() && this.mode != State.WAITING) {
			return true;
		} else if (waterLevel < minSafteyLimit() && heaterOn == true) {
			return true;
		}
		return false;
	}
	
	/**
	 * detects incoming repaired messages from physical units / operator desk
	 * resets programs booleans depending on message
	 * 
	 * @param incoming - Incoming messages checks for any repair message
	 * @param outgoing - Outgoing mailbox to send repair acknowledgement message
	 * @return boolean - True if repair message has been detected
	 */
	private boolean detectRepair(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
		Message[] pumpRepaired = extractAllMatches(MessageKind.PUMP_REPAIRED_n, incoming);
		Message[] pumpControllerRepaired = extractAllMatches(MessageKind.PUMP_CONTROL_REPAIRED_n, incoming);
		Message waterLevelRepaired = extractOnlyMatch(MessageKind.LEVEL_REPAIRED, incoming);
		Message steamLevelRepaired = extractOnlyMatch(MessageKind.STEAM_REPAIRED, incoming);
		

		for(int i = 0; i < pumpRepaired.length; i++) {
			if(pumpRepaired[i] != null) {
				int pumpNo = pumpRepaired[i].getIntegerParameter();
				outgoing.send(new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n,pumpNo));
				pumpFailureDetected[pumpNo] = false;
				return true;
			}
		}
		for(int i = 0; i < pumpControllerRepaired.length; i++) {
			if(pumpControllerRepaired[i] != null) {
				int pumpControllerNo = pumpControllerRepaired[i].getIntegerParameter();
				outgoing.send(new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n,pumpControllerNo));
				pumpControllerFailureDetected[pumpControllerNo] = false;
				return true;
			}
		}
		if(steamLevelRepaired != null) {
			outgoing.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
			steamLevelFailure = false;
			return true;
		}
		if(waterLevelRepaired != null) {
			outgoing.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
			waterLevelFailure = false;
			return true;
		}
		return false;
	}
	
	/**
	 * uses the current water level, plus pump capacity minus the steam produced to determine to min/max and average values per no of pumps open
	 * stores these value in an array of doubles. checks how many pumps need to be opened to reach the optimal level, whilst also making sure it 
	 * will not go over the max 
	 * 
	 * @param waterLevel       - Double representative of the water level given by the level sensor
	 * @param steamLevel       - Double representative of the stem production given by the steam sensor
	 * @return pumpsToActivate - Integer, number of pumps to activate -1 indicates nil pumps to open
	 */
	private int pumpsToActivate(Double waterLevel, Double steamLevel) {
		int pumpsToActivate = -1;
		int pumpsAvaliable = getNoOfPumps();
		
		double[] proximtyToOptimal = new double[pumpsAvaliable];
		double[] maxPerPumpsOpen = new double[pumpsAvaliable];
		double[] minPerPumpsOpen = new double[pumpsAvaliable];
		
		double levelDecepency = configuration.getCapacity();
		
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
		
		//if current water level is below minimum level turn all pumps on
		if(waterLevel < minWaterLevel()) {
			return pumpsAvaliable;
		} else {
			//Determines how many pumps to activate based on possible ranges
			for(int i = 0; i < pumpsAvaliable;i++) {
				if(maxPerPumpsOpen[i] < maxWaterLevel()	&& minPerPumpsOpen[i] > minWaterLevel() && proximtyToOptimal[i] < levelDecepency) {
					pumpsToActivate = i;
					maxPossibleWaterLevel = maxPerPumpsOpen[i];
					minPossibleWaterLevel = minPerPumpsOpen[i];
					levelDecepency = proximtyToOptimal[i];
				}
			}
		}
		return pumpsToActivate;
	}
	
	/**
	 * Helper method used to return true capacity of pumps, useful if not all pumps have the 
	 * same capacity. i.e. half capacity etc
	 * 
	 * @param numberOfPumps      - which/how many of the pumps capacity is required
	 * @return pumpTotalCapacity - Double representing the sum of the selected pumps capacity
	 */
	private double getPumpTotalCapacity(int numberOfPumps) {
		int pumpTotalCapacity = 0;
		
		for(int i = 0; i < numberOfPumps; i++) {
			pumpTotalCapacity += configuration.getPumpCapacity(i);
		}
		
		return pumpTotalCapacity;
	}
	
	/**
	 * Returns an integer indicating the amount of pumps open
	 * 
	 * @param pumpStates - Messages received from the pumps
	 * @return integer   - Represents current pumps open
	 */
	private int[] getPumpsOpen(Message[] pumpState) {
		int[] currentPumpsOpen = Arrays.stream(pumpState).filter(m -> m.getBooleanParameter()).mapToInt(m -> m.getIntegerParameter()).toArray();
		return currentPumpsOpen;
	}
	
	/**
	 * Returns an integer indicating the amount of pumps open
	 * 
	 * @param pumpStates - Messages received from the pumps
	 * @return integer   - Represents the current pumps closed
	 */
	private int[] getPumpsClosed(Message[] pumpState) {
		int[] currentPumpsClosed = Arrays.stream(pumpState).filter(m -> !m.getBooleanParameter()).mapToInt(m -> m.getIntegerParameter()).toArray();
		return currentPumpsClosed;
	}
	
	/**
	 * Method used to turn pumps on or off as required, this method can also account for pump failure
	 * since it operates by knowing what pumps are already open or closed, even if it is caused by 
	 * pump or controller failure, the method will account for this when selecting what pumps to close/open
	 * 
	 * @param openPumpArray   - Array of integers, indicating what pumps are open
	 * @param closedPumpArray - Array of integers, indicating what pumps are closed
	 * @param pumpsToActivate - Integer indicating how many pumps are needed open
	 * @param outgoing        - Mailbox to send messages to toggle pumps
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
	 * used during emergency shut down to close all pumps
	 * 
	 * @param outgoing - Mailbox to send messages to close all pumps
	 */
	private void closeAllPumps(Mailbox outgoing) {
		for(int i = 0; i < getNoOfPumps(); i++) {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n,i));
		}
	}
	
	/**
	 * method used to estimate water level if the sensor breaks based of last known reading
	 * for first cycle it will use the last reading provided by the sensor
	 * after first cycle it will use the max per pump opened as an estimated of the water level
	 * 
	 * @param pumpsActive - Pumps active used to get incoming water
	 * @param waterLevel  - Water level see above
	 * @param steamLevel  - Steam level reading sent by steam sensor 
	 * @return double     - Estimated water level in lieu of the water level sensor reading
	 */
	private double estimateWaterLevel(int pumpsActive, double waterLevel, double steamLevel) {
		double maxPossibleWaterLevel = getMaxLevelPerPump(waterLevel, pumpsActive, steamLevel);
		double minPossibleWaterLevel = getMinLevelPerPump(waterLevel, pumpsActive);
		return ((maxPossibleWaterLevel + minPossibleWaterLevel) / 2);
	}
	
	/**
	 * method used to estimate steam level production, when steam level fails
	 * 
	 * @param lastKnownWaterLevel - Water level of the last cycle
	 * @param waterLevel          - Water level of current cycle
	 * @param pumpsOpen			  - Number of pumps open on current cycle
	 * @return double             - Estimation of steam production
	 */
	private double estimateSteamLevel(double lastKnownWaterLevel, double waterLevel, int[] pumpsOpen) {
		double openPumpCapacity = 0.0;
		for(int i = 0; i < pumpsOpen.length; i++) {
			openPumpCapacity += configuration.getPumpCapacity(pumpsOpen[i]);
		}
		double estimateSteamLevel = ((lastKnownWaterLevel + (openPumpCapacity)) - waterLevel);
		
		if(estimateSteamLevel > configuration.getMaximualSteamRate()) {
			return configuration.getMaximualSteamRate();
		}
		return estimateSteamLevel;
	}
	
	/**
	 * helper method used to calculate the max possible water level after one cycle
	 * 
	 * @param waterLevel  - Current water level, either by sensor reading or estimateWaterLevel method
	 * @param pumpsActive - Integer indicating how many pumps will be opened
	 * @param steamLevel  - Current steam output level given via sensor
	 * @return double     - Max possible water level after one cycle
	 */
	private double getMaxLevelPerPump(double waterLevel, int pumpsActive, double steamLevel) {
		return waterLevel + (5 * getPumpTotalCapacity(pumpsActive)) - (5 * steamLevel);
	}
	
	/**
	 * helper method used to calculate the minimum possible water level after one cycle
	 * 
	 * @param waterLevel  - Current water level, either by sensor reading or estimateWaterLevel method
	 * @param pumpsActive - Integer indicating how many pumps will be opened
	 * @return double     - Minimum possible water level after one cycle
	 */
	private double getMinLevelPerPump(double waterLevel, int pumpsActive) {
		return waterLevel + (5 * getPumpTotalCapacity(pumpsActive)) - (5 * configuration.getMaximualSteamRate());
	}
	
	/**
	 * helper method to return true if pump controller has opened pump
	 * 
	 * @param pumpControllerState - Pump Controller Messages
	 * @param pumpNo              - Integer indicating the pump number 
	 * @return boolean            - True, pump open, false pump closed
	 */
	private boolean getPumpControllerState(Message[] pumpControllerState,int pumpNo) {
		return pumpControllerState[pumpNo].getBooleanParameter();
	}
	
	/**
	 * helper Method to return if true if the pump indicates its open
	 * 
	 * @param pumpState - Messages sent by pump
	 * @param pumpNo    - Integer indicating the pump number
	 * @return boolean  - True if pump is open, false if pump is closed
	 */
	private boolean getPumpState(Message[] pumpState,int pumpNo) {
		return pumpState[pumpNo].getBooleanParameter();
	}
	
	/**
	 * checks the water level reading given by the water level sensor is within acceptable limits
	 * 
	 * @param waterLevel - Double parameter received by the level sensor
	 * @return boolean   - True, if water is within the acceptable range
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
	 * @return integer - Number of pumps in the configuration
	 */
	private int getNoOfPumps() {
		return configuration.getNumberOfPumps();
	}
	
	/**
	 * helper method, returns the minimal normal level
	 * 
	 * @return double - Minimal normal water level
	 */
	private double minWaterLevel() {
		return configuration.getMinimalNormalLevel();
	}
	
	/**
	 * helper method, returns the maximal normal level
	 * 
	 * @return double - Maximal normal water level
	 */
	private double maxWaterLevel() {
		return configuration.getMaximalNormalLevel();
	}
	
	/**
	 * helper method, returns the optimal water level
	 * max water level plus minimal water level divided by two
	 * 
	 * @return double - Optimal water level
	 */
	private double optimalWaterLevel() {
		return (configuration.getMaximalNormalLevel() + configuration.getMinimalNormalLevel())/2;
	}
	
	/**
	 * helper method, returns the maximum safe water level
	 * 
	 * @return double - Max water level
	 */
	private double maxSafteyLimit() {
		return configuration.getMaximalLimitLevel();
	}
	
	/**
	 * helper method, returns the minimum safe water level
	 * 
	 * @return double - Minimum water level
	 */
	private double minSafteyLimit() {
		return configuration.getMinimalLimitLevel();
	}	
}

