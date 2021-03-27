package steam.boiler.core;

import static org.junit.Assert.assertNotNull;

import java.util.Arrays;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

//import com.sun.tools.javac.code.Attribute.Array;

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
	 * boolean that indicated is the emptying valve is open or closed.
	 */
	private boolean emptyingTank = false;

	/**
	 * doubles that store the last reading sent by the steam and water level
	 * sensors.
	 */
	private double lastKnownWaterLevel = 0.0;
	private double lastKnownSteamLevel = 0.0;

	/**
	 * doubles to store the max water level per pumps active.
	 */
	private double maxPossibleWaterLevel; // initialised in MySteamBoilerController
	private double minPossibleWaterLevel = 0.0;

	/**
	 * set of boolean arrays used to determine pump behavior, can be checked against
	 * incoming messages to determine if pump or controller has malfunctioned.
	 */
	private boolean[] pumpKnownState;
	private boolean[] pumpFailureDetected;
	private boolean[] pumpControllerFailureDetected;

	/**
	 * boolean to track steam level and water level sensor failures.
	 */
	private boolean steamLevelFailure = false;
	private boolean waterLevelFailure = false;

	/**
	 * boolean to track steam boiler heater operation.
	 */
	private boolean heaterOn = false;

	/**
	 * Construct a steam boiler controller for a given set of characteristics.
	 *
	 * @param configuration The boiler characteristics to be used.
	 */
	public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
		this.configuration = configuration;
		int numberOfPumps = configuration.getNumberOfPumps();
		this.maxPossibleWaterLevel = configuration.getCapacity();
		this.pumpKnownState = new boolean[numberOfPumps];
		this.pumpFailureDetected = new boolean[numberOfPumps];
		this.pumpControllerFailureDetected = new boolean[numberOfPumps];
		Arrays.fill(this.pumpKnownState, false);
		Arrays.fill(this.pumpFailureDetected, false);
		Arrays.fill(this.pumpControllerFailureDetected, false);
	}

	/**
	 * This message is displayed in the simulation window, and enables a limited
	 * form of debug output. The content of the message has no material effect on
	 * the system, and can be whatever is desired. In principle, however, it should
	 * display a useful message indicating the current state of the controller.
	 *
	 * @return String - status message.
	 */
	@Override
	public String getStatusMessage() {
		String currentMode = this.mode.toString();
		assertNotNull(currentMode);
		return currentMode;
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
		// extract waiting message, and physical unit ready message
		Message boilerWaiting = extractOnlyMatch(MessageKind.STEAM_BOILER_WAITING, incoming);
		Message physicalUnitReady = extractOnlyMatch(MessageKind.PHYSICAL_UNITS_READY, incoming);

		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		// If no transmission failure has occurred
		if (this.mode != State.EMERGENCY_STOP) {
			// Asserts level and steam message is not null
			assertNotNull(levelMessage);
			assertNotNull(steamMessage);
			// Detect an repairs, resets to normal mode, will be overridden if more failures
			// are present
			if (detectRepair(incoming, outgoing)) {
				outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
				this.mode = State.NORMAL;
			}
			// Detects a pump, controller or steam sensor failure and sets mode to degraded
			if ((detectPumpFailure(levelMessage, pumpStateMessages, pumpControlStateMessages, outgoing)
					|| detectSteamLevelFailure(steamMessage, outgoing)) && this.mode == State.NORMAL) {
				this.mode = State.DEGRADED;
			}
			// Detects a level sensor failure, sets mode to rescue
			if (dectectWaterLevelFailure(levelMessage, outgoing) && this.mode == State.NORMAL) {
				this.mode = State.RESCUE;
			}
			// Detects if Imminent failure could occur i.e. water level too high/low, steam
			// and water sensor combined failure
			if (detectImminentFailure(levelMessage.getDoubleParameter())) {
				closeAllPumps(outgoing);
				emergencyShutdown(outgoing);
				this.mode = State.EMERGENCY_STOP;
			}
			// WAITING MODE
			if (this.mode == State.WAITING) {
				waitingMode(steamMessage, levelMessage, boilerWaiting, pumpStateMessages, outgoing);
			}
			// READY MODE
			if (this.mode == State.READY) {
				readyMode(physicalUnitReady, outgoing);
			}
			// NORMAL MODE
			if (this.mode == State.NORMAL) {
				normalOperationMode(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages, outgoing);
			}
			// DEGRADED MODE
			if (this.mode == State.DEGRADED) {
				degradeOperationalMode(levelMessage, steamMessage, pumpStateMessages, outgoing);
			}
			// RESCUE MODE
			if (this.mode == State.RESCUE) {
				rescueOperationalMode(pumpStateMessages, steamMessage, outgoing);
			}
			// Update global double variables lastKnownSteamLevel and lastKnownSteamLevel
			updateExpectedLevels(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter());
		}
		// EMERGENCY MODE
		else if (this.mode == State.EMERGENCY_STOP) {
			closeAllPumps(outgoing);
			emergencyShutdown(outgoing);
		}
	}

	/**
	 * Updates the lastKnownWaterLevel and lastKnownSteamLevel provided the sensor
	 * is still operating.
	 * 
	 * @param waterLevel - Double parameter received from the level sensor.
	 * @param steamLevel - Double parameter received from the steam sensor.
	 */
	private void updateExpectedLevels(double waterLevel, double steamLevel) {
		// Asserts level sensor is operational
		if (!this.waterLevelFailure) {
			this.lastKnownWaterLevel = waterLevel;
		}
		// Asserts steam sensor is operational
		if (!this.steamLevelFailure) {
			this.lastKnownSteamLevel = steamLevel;
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
	private boolean transmissionFailure(@Nullable Message levelMessage, @Nullable Message steamMessage,
			Message[] pumpStates, Message[] pumpControlStates) {
		// Check level readings
		if (levelMessage == null) {
			// Nonsense or missing level reading
			return true;
		} else if (steamMessage == null) {
			// Nonsense or missing steam reading
			return true;
		} else if (pumpStates.length != this.configuration.getNumberOfPumps()) {
			// Nonsense pump state readings
			return true;
		} else if (pumpControlStates.length != this.configuration.getNumberOfPumps()) {
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
	 * Waiting mode, checks for failure of any critical system such as water level
	 * sensor or steam level failure asserts the boiler is in a waiting state. Then
	 * begins to fill the tank via initialisationMode().
	 * 
	 * @param steamMessage      - Message received from the steam level sensor
	 * @param levelMessage      - Message received from the water level sensor
	 * @param boilerWaiting     - Message containing the boiler waiting message, if
	 *                          it exists
	 * @param pumpStateMessages - Messages received from the pumps
	 * @param outgoing          - Mailbox to sent require messages
	 */
	private void waitingMode(Message steamMessage, Message levelMessage, @Nullable Message boilerWaiting,
			Message[] pumpStateMessages, Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
		// Checks for possible failures that could be safety critical
		if (steamMessage.getDoubleParameter() != 0.0 || this.waterLevelFailure || this.steamLevelFailure) {
			closeAllPumps(outgoing);
			emergencyShutdown(outgoing);
		}
		// Asserts boiler is waiting to begin
		if (boilerWaiting != null) {
			initialisationMode(levelMessage, steamMessage, pumpStateMessages, outgoing);
		}
	}

	/**
	 * Ready mode, send to the physical units that the program is ready to begin
	 * operating waits to receive a message from the physical units that they are
	 * ready to begin sets mode to normal, thus normal operation can begin via
	 * normalOperationMode().
	 * 
	 * @param physicalUnitReady - Message stating the physical units are ready to
	 *                          begin operating
	 * @param outgoing          - Mailbox to send required messages
	 */
	private void readyMode(@Nullable Message physicalUnitReady, Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.PROGRAM_READY));
		// checks physical units are ready
		if (physicalUnitReady != null) {
			this.heaterOn = true;
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
			this.mode = State.NORMAL;
		}
	}

	/**
	 * Initialistion Mode turns on all the pumps, checking functionality, then
	 * closes pumps as the boiler\ begins to fill, shuts all pumps when boiler is
	 * above optimal level (half capacity).
	 * 
	 * @param waterLevel - Double indicating current water level reading given via
	 *                   sensor.
	 * @param outgoing   - Outgoing mailbox to send pump controllers commands.
	 */
	private void initialisationMode(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Mailbox outgoing) {
		double waterLevel = levelMessage.getDoubleParameter();
		double steamLevel = steamMessage.getDoubleParameter();
		// Make sure emptying valve is closed before starting to fill tank
		if (this.emptyingTank && waterLevel < maxWaterLevel()) {
			outgoing.send(new Message(MessageKind.VALVE));
			this.emptyingTank = false;
		}

		// Begin filling tank
		int pumpsToActivate = pumpsToActivate(waterLevel, steamLevel);
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);

		// Checks water level is within minimal and max normal water level
		if (waterLevel > minWaterLevel() && waterLevel < maxWaterLevel()) {
			this.mode = State.READY;
		} else if (waterLevel > maxWaterLevel()) {
			outgoing.send(new Message(MessageKind.VALVE));
			this.emptyingTank = true;
		}
	}

	/**
	 * Simple control method used to maintain water level during normal operating
	 * conditions.
	 * 
	 * @param waterLevel           - Double parameter received from the level
	 *                             sensor.
	 * @param steamLevel           - Double parameter received from the steam
	 *                             sensor.
	 * @param pumpStates           - Messages received from the pumps.
	 * @param pumpControllerStates - Messages received from the pump controllers.
	 * @param outgoing             - Mailbox to send required messages.
	 */
	private void normalOperationMode(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Message[] pumpControllerStates, Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
		// Normal operation of pumps
		int pumpsToActivate = pumpsToActivate(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter());
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
	}

	/**
	 * Control method for operating in a degraded state, if steam level sensor has
	 * failed the method will estimate the steam level else system will operate as
	 * normal, as turnPumpsOnOff can account for pump/controller failure.
	 * 
	 * @param waterLevel - Double parameter received from the level sensor.
	 * @param steamLevel - Double parameter received from the steam sensor.
	 * @param pumpStates - Messages received from the pumps.
	 * @param outgoing   - Mailbox to send required messages.
	 */
	private void degradeOperationalMode(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
		double estimatedSteamLevel;
		// If degraded mode is due to steam sensor failure, system will estimate the
		// steam level
		if (this.steamLevelFailure) {
			estimatedSteamLevel = estimateSteamLevel(this.lastKnownWaterLevel, levelMessage.getDoubleParameter(),
					getPumpsOpen(pumpStates));
		} else {
			estimatedSteamLevel = steamMessage.getDoubleParameter();
		}
		// Normal operation of pumps with new parameters, if required
		int pumpsToActivate = pumpsToActivate(levelMessage.getDoubleParameter(), estimatedSteamLevel);
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
	}

	/**
	 * Control method for operation in rescue mode, uses an algorithm to estimate
	 * the water level for the first cycle, it uses the last known water level given
	 * by the operational level sensor after that the lastKnownWaterLevel is updated
	 * to the estimated level.
	 * 
	 * @param pumpStates - Messages received from the pumps.
	 * @param steamLevel - Double parameter received from the steam sensor.
	 * @param outgoing   - Mailbox to send required messages.
	 */
	private void rescueOperationalMode(Message[] pumpStates, Message steamMessage, Mailbox outgoing) {
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
		int activePumps = getPumpsOpen(pumpStates).length;
		int pumpsToActivate = pumpsToActivate(this.lastKnownWaterLevel, steamMessage.getDoubleParameter());
		turnPumpsOnOff(getPumpsOpen(pumpStates), getPumpsClosed(pumpStates), pumpsToActivate, outgoing);
		// Update lastKnownWaterLevel to be used in next cycle
		double estimatedWaterLevel = estimateWaterLevel(activePumps, this.lastKnownWaterLevel,
				steamMessage.getDoubleParameter());
		this.lastKnownWaterLevel = estimatedWaterLevel;
	}

	/**
	 * Emergency shutdown, sends a stop message, then starts emptying the tank.
	 * 
	 * @param outgoing - Outgoing mailbox to send stop and valve opening message.
	 */
	private void emergencyShutdown(Mailbox outgoing) {
		// Send emergency stop message three times
		for (int i = 0; i <= 2; i++) {
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
		}
		// Indicate to this program that heater is off
		this.heaterOn = false;
		// Begin emptying tank
		if (!this.emptyingTank) {
			outgoing.send(new Message(MessageKind.VALVE));
			this.emptyingTank = true;
		}
	}

	/**
	 * Checks pump states given by the pump itself and controller against the know
	 * state held by the program also confirms if the water level is within expected
	 * limits to determine if a failure has occurred.
	 * 
	 * @param waterLevel           - Double parameter received from the level
	 *                             sensor.
	 * @param pumpStates           - Messages received from the pumps.
	 * @param pumpControllerStates - Messages received from the pump controllers.
	 * @param outgoing             - Mailbox to send required messages.
	 */
	private boolean detectPumpFailure(Message levelMessage, Message[] pumpStates, Message[] pumpControllerStates,
			Mailbox outgoing) {
		double waterLevel = levelMessage.getDoubleParameter();

		for (int i = 0; i < getNoOfPumps(); i++) {
			// FAIL - case 3 - controller
			if (getPumpState(pumpStates, i) == this.pumpKnownState[i]
					&& getPumpControllerState(pumpControllerStates, i) != this.pumpKnownState[i]
					&& waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
				this.pumpControllerFailureDetected[i] = true;
				return true;
			}
			// FAIL - case 4 - pump
			else if (getPumpState(pumpStates, i) == this.pumpKnownState[i]
					&& getPumpControllerState(pumpControllerStates, i) != this.pumpKnownState[i]
					&& !waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				this.pumpFailureDetected[i] = true;
				return true;
			}
			// FAIL - case 5 - pump
			else if (getPumpState(pumpStates, i) != this.pumpKnownState[i]
					&& getPumpControllerState(pumpControllerStates, i) == this.pumpKnownState[i]
					&& waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				this.pumpFailureDetected[i] = true;
				return true;
			}
			// FAIL - case 6 - pump
			else if (getPumpState(pumpStates, i) != this.pumpKnownState[i]
					&& getPumpControllerState(pumpControllerStates, i) == this.pumpKnownState[i]
					&& !waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				this.pumpFailureDetected[i] = true;
				return true;
			}
			// FAIL - case 7 - pump
			else if (getPumpState(pumpStates, i) != this.pumpKnownState[i]
					&& getPumpControllerState(pumpControllerStates, i) != this.pumpKnownState[i]
					&& waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				this.pumpFailureDetected[i] = true;
				return true;
			}
			// FAIL - case 8 - pump
			else if (getPumpState(pumpStates, i) != this.pumpKnownState[i]
					&& getPumpControllerState(pumpControllerStates, i) != this.pumpKnownState[i]
					&& !waterLevelWithinLimits(waterLevel)) {
				outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
				this.pumpFailureDetected[i] = true;
				return true;
			}
		}
		return false;
	}

	/**
	 * Detects if the steam level sensor is outside of the limits or if the steam
	 * level has decreased, indicating a failure of the steam sensor.
	 * 
	 * @param steamLevel - Double given by the steam level sensor.
	 * @param outgoing   - Outgoing mailbox to send failure detection message.
	 * @return boolean - True indicates steam sensor failure.
	 */
	private boolean detectSteamLevelFailure(Message steamMessage, Mailbox outgoing) {
		double steamLevel = steamMessage.getDoubleParameter();
		// FAIL - Steam sensor
		if (steamLevel < 0.0 || steamLevel > this.configuration.getMaximualSteamRate()
				|| steamLevel < this.lastKnownSteamLevel) {
			this.steamLevelFailure = true;
			outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
			return true;
		}
		return false;
	}

	/**
	 * Detects if the water level sensor has failed, initially checks issue is not
	 * caused by pump or pump controller failure.
	 * 
	 * @param waterLevel - Incoming water level sent by the sensor.
	 * @param outgoing   - Outgoing mailbox to send level sensor failure detection
	 *                   message.
	 * @return boolean - True indicates level sensor failure.
	 */
	private boolean dectectWaterLevelFailure(Message waterLevel, Mailbox outgoing) {
		boolean pumpOrControllerFailure = false;

		// Checks water level discrepancy is not caused by pump or controller failure
		for (int i = 0; i < this.pumpFailureDetected.length; i++) {
			if (this.pumpControllerFailureDetected[i] == true) {
				pumpOrControllerFailure = true;
			} else if (this.pumpFailureDetected[i] == true) {
				pumpOrControllerFailure = true;
			}
		}

		// Checks for possible issues cause by level sensor failure
		// Checks level reading is not outside possible ranges
		// FAIL - case 2 - level sensor
		if (waterLevel.getDoubleParameter() < 0.0
				|| waterLevel.getDoubleParameter() > this.configuration.getCapacity()) {
			this.waterLevelFailure = true;
			outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			return true;
		}

		// Checks water level is outside expected range and not caused by pump or
		// controller failure
		// Asserts boiler is operating, else false positive will be triggered during
		// initialisationMode()
		// FAIL - case 2 - level sensor
		else if (!waterLevelWithinLimits(waterLevel.getDoubleParameter()) && !pumpOrControllerFailure
				&& this.heaterOn) {
			this.waterLevelFailure = true;
			outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
			return true;
		}
		return false;
	}

	/**
	 * Detects if the current failure(s) which can result in critical failure has
	 * occurred which will trigger an emergency shut down. A critical failure is the
	 * steam and the level sensor failing at the same time as one is used to
	 * estimate the other. Since there are a number of pumps i.e. a pump (or
	 * multiple) failing does not have the potential to cause a critical failure,
	 * however if multiple pump failures cause the water level to exceeds the safety
	 * limits a critical failure could occur thus the method will trigger an
	 * emergency shut down.
	 * 
	 * @param waterLevel - Double parameter received by water level sensor.
	 * @return boolean - True means multiply failures have been detected.
	 */
	private boolean detectImminentFailure(double waterLevel) {
		double currentWaterLevel;
		// If level sensor has failed, uses lastKnownWaterLevel
		if (this.waterLevelFailure) {
			currentWaterLevel = this.lastKnownWaterLevel;
		} else {
			currentWaterLevel = waterLevel;
		}
		// Checks for multiple critical system failures
		if (this.waterLevelFailure && this.steamLevelFailure) {
			return true;
		}
		// Checks water level is inside the safety limits
		else if (currentWaterLevel > maxSafteyLimit() && this.mode != State.WAITING) {
			return true;
		} else if (currentWaterLevel < minSafteyLimit() && this.heaterOn == true) {
			return true;
		}
		return false;
	}

	/**
	 * Detects incoming repaired messages from physical units / operator desk resets
	 * programs booleans depending on message.
	 * 
	 * @param incoming - Incoming messages checks for any repair message.
	 * @param outgoing - Outgoing mailbox to send repair acknowledgement message.
	 * @return boolean - True if repair message has been detected.
	 */
	private boolean detectRepair(Mailbox incoming, Mailbox outgoing) {
		Message[] pumpRepaired = extractAllMatches(MessageKind.PUMP_REPAIRED_n, incoming);
		Message[] pumpControllerRepaired = extractAllMatches(MessageKind.PUMP_CONTROL_REPAIRED_n, incoming);
		Message waterLevelRepaired = extractOnlyMatch(MessageKind.LEVEL_REPAIRED, incoming);
		Message steamLevelRepaired = extractOnlyMatch(MessageKind.STEAM_REPAIRED, incoming);
		// Check for pump repair messages
		for (int i = 0; i < pumpRepaired.length; i++) {
			if (pumpRepaired[i] != null) {
				int pumpNo = pumpRepaired[i].getIntegerParameter();
				outgoing.send(new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n, pumpNo));
				this.pumpFailureDetected[pumpNo] = false;
				return true;
			}
		}
		// Check for controller repair message
		for (int i = 0; i < pumpControllerRepaired.length; i++) {
			if (pumpControllerRepaired[i] != null) {
				int pumpControllerNo = pumpControllerRepaired[i].getIntegerParameter();
				outgoing.send(new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n, pumpControllerNo));
				this.pumpControllerFailureDetected[pumpControllerNo] = false;
				return true;
			}
		}
		// Check for steam sensor repair message
		if (steamLevelRepaired != null) {
			outgoing.send(new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT));
			this.steamLevelFailure = false;
			return true;
		}
		// Check for level sensor repair
		if (waterLevelRepaired != null) {
			outgoing.send(new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT));
			this.waterLevelFailure = false;
			return true;
		}
		return false;
	}

	/**
	 * Uses the current water level, plus pump capacity minus the steam produced to
	 * determine to min/max and average values per no of pumps open stores these
	 * value in an array of doubles. checks how many pumps need to be opened to
	 * reach the optimal level, whilst also making sure it will not go over the max.
	 * 
	 * @param waterLevel - Double representative of the water level given by the
	 *                   level sensor.
	 * @param steamLevel - Double representative of the stem production given by the
	 *                   steam sensor.
	 * @return pumpsToActivate - Integer, number of pumps to activate -1 indicates
	 *         nil pumps to open.
	 */
	private int pumpsToActivate(double waterLevel, double steamLevel) {
		int pumpsToActivate = -1;
		int pumpsAvaliable = getNoOfPumps();
		// Arrays to store predicted water levels per pumps
		double[] proximtyToOptimal = new double[pumpsAvaliable];
		double[] maxPerPumpsOpen = new double[pumpsAvaliable];
		double[] minPerPumpsOpen = new double[pumpsAvaliable];
		// Double used as stand in for distance between average water level per pump and
		// optimal water level
		double levelDiscrepancy = this.configuration.getCapacity();

		// Calculate the possible range of the water levels per pump(s) activated
		for (int i = 0; i < pumpsAvaliable; i++) {
			maxPerPumpsOpen[i] = getMaxLevelPerPump(waterLevel, i, steamLevel);
			minPerPumpsOpen[i] = getMinLevelPerPump(waterLevel, i);
			proximtyToOptimal[i] = Math.abs(((maxPerPumpsOpen[i] + minPerPumpsOpen[i]) / 2) - optimalWaterLevel());
		}

		// If current water level is above max normal level, returns -1 which will shut
		// all pumps
		if (waterLevel >= maxWaterLevel()) {
			return pumpsToActivate;
		}

		// If current water level is below minimum level turn all pumps on
		if (waterLevel < minWaterLevel()) {
			return pumpsAvaliable;
		}
		// Determines how many pumps to activate based on possible ranges
		for (int i = 0; i < pumpsAvaliable; i++) {
			if (maxPerPumpsOpen[i] < maxWaterLevel() && minPerPumpsOpen[i] > minWaterLevel()
					&& proximtyToOptimal[i] < levelDiscrepancy) {
				pumpsToActivate = i;
				this.maxPossibleWaterLevel = maxPerPumpsOpen[i];
				this.minPossibleWaterLevel = minPerPumpsOpen[i];
				levelDiscrepancy = proximtyToOptimal[i];
			}
		}
		return pumpsToActivate;
	}

	/**
	 * Helper method used to return true capacity of pumps, useful if not all pumps
	 * have the same capacity.
	 * 
	 * @param numberOfPumps - which/how many of the pumps capacity is required.
	 * @return pumpTotalCapacity - Double representing the sum of the selected pumps
	 *         capacity.
	 */
	private double getPumpTotalCapacity(int numberOfPumps) {
		int pumpTotalCapacity = 0;

		for (int i = 0; i < numberOfPumps; i++) {
			pumpTotalCapacity += this.configuration.getPumpCapacity(i);
		}
		return pumpTotalCapacity;
	}

	/**
	 * Returns an integer indicating the amount of pumps open.
	 * 
	 * @param pumpStates - Messages received from the pumps.
	 * @return integer - Represents current pumps open.
	 */
	private static int[] getPumpsOpen(Message[] pumpState) {
		int[] currentPumpsOpen = Arrays.stream(pumpState).filter(m -> m.getBooleanParameter())
				.mapToInt(m -> m.getIntegerParameter()).toArray();
		assertNotNull(currentPumpsOpen);
		return currentPumpsOpen;
	}

	/**
	 * Returns an integer indicating the amount of pumps open.
	 * 
	 * @param pumpStates - Messages received from the pumps.
	 * @return integer - Represents the current pumps closed.
	 */
	private static int[] getPumpsClosed(Message[] pumpState) {
		int[] currentPumpsClosed = Arrays.stream(pumpState).filter(m -> !m.getBooleanParameter())
				.mapToInt(m -> m.getIntegerParameter()).toArray();
		assertNotNull(currentPumpsClosed);
		return currentPumpsClosed;
	}

	/**
	 * Method used to turn pumps on or off as required, this method can also account
	 * for pump failure since it operates by knowing what pumps are already open or
	 * closed, even if it is caused by pump or controller failure, the method will
	 * account for this when selecting what pumps to close/open.
	 * 
	 * @param openPumpArray   - Array of integers, indicating what pumps are open.
	 * @param closedPumpArray - Array of integers, indicating what pumps are closed.
	 * @param pumpsToActivate - Integer indicating how many pumps are needed open.
	 * @param outgoing        - Mailbox to send messages to toggle pumps.
	 */
	private void turnPumpsOnOff(int[] openPumpArray, int[] closedPumpArray, int pumpsToActivate, Mailbox outgoing) {
		int currentPumpsOpen = openPumpArray.length;
		int currentPumpsClosed = closedPumpArray.length;
		int pumpsToOpen = pumpsToActivate;
		// Closes pumps if required
		if (currentPumpsOpen > pumpsToActivate) {
			for (int i = currentPumpsOpen - 1; i >= 0; i--) {
				int pumpNo = openPumpArray[i];
				if (currentPumpsOpen > pumpsToActivate && !this.pumpFailureDetected[pumpNo]) {
					outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, pumpNo));
					this.pumpKnownState[pumpNo] = false;
					currentPumpsOpen--;
				}
			}
		}
		// Opens pumps if required
		else {
			for (int i = 0; i < currentPumpsClosed; i++) {
				int pumpNo = closedPumpArray[i];
				if (currentPumpsOpen < pumpsToOpen && !this.pumpFailureDetected[pumpNo]) {
					outgoing.send(new Message(MessageKind.OPEN_PUMP_n, pumpNo));
					this.pumpKnownState[pumpNo] = true;
					pumpsToOpen--;
				}
			}
		}
	}

	/**
	 * Used during emergency shut down to close all pumps.
	 * 
	 * @param outgoing - Mailbox to send messages to close all pumps
	 */
	private void closeAllPumps(Mailbox outgoing) {
		for (int i = 0; i < getNoOfPumps(); i++) {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
		}
	}

	/**
	 * Method used to estimate water level, returns the max possible water level to
	 * prevent over filling the tank.
	 * 
	 * @param pumpsActive - Pumps active used to get incoming water.
	 * @param waterLevel  - Water level from lastKnownWaterLevel.
	 * @param steamLevel  - Steam level reading sent by steam sensor.
	 * @return double - Estimated water level in lieu of the water level sensor
	 *         reading.
	 */
	private double estimateWaterLevel(int pumpsActive, double waterLevel, double steamLevel) {
		return getMaxLevelPerPump(waterLevel, pumpsActive, steamLevel);
	}

	/**
	 * Method used to estimate steam level production, when steam level fails using
	 * the difference between the lastKnownWaterLevel plus the capacity of all the
	 * pumps open and the current waterLevel to estimated the steam level.
	 *
	 * @param lastCycleWaterLevel - Water level of the last cycle.
	 * @param waterLevel          - Water level of current cycle.
	 * @param pumpsOpen           - Number of pumps open on current cycle.
	 * @return double - Estimation of steam production.
	 */
	private double estimateSteamLevel(double lastCycleWaterLevel, double waterLevel, int[] pumpsOpen) {
		double openPumpCapacity = 0.0;
		for (int i = 0; i < pumpsOpen.length; i++) {
			openPumpCapacity += this.configuration.getPumpCapacity(pumpsOpen[i]);
		}

		double estimateSteamLevel = ((lastCycleWaterLevel + (openPumpCapacity)) - waterLevel);
		// To account for discrepancy between readings and calculations
		if (estimateSteamLevel > this.configuration.getMaximualSteamRate()) {
			return this.configuration.getMaximualSteamRate();
		}
		return estimateSteamLevel;
	}

	/**
	 * Helper method used to calculate the max possible water level after one cycle.
	 * 
	 * @param waterLevel  - Current water level, either by sensor reading or
	 *                    estimateWaterLevel method.
	 * @param pumpsActive - Integer indicating how many pumps will be opened.
	 * @param steamLevel  - Current steam output level given via sensor.
	 * @return double - Max possible water level after one cycle.
	 */
	private double getMaxLevelPerPump(double waterLevel, int pumpsActive, double steamLevel) {
		return (waterLevel + (5 * getPumpTotalCapacity(pumpsActive))) - (5 * steamLevel);
	}

	/**
	 * Helper method used to calculate the minimum possible water level after one
	 * cycle.
	 * 
	 * @param waterLevel  - Current water level, either by sensor reading or
	 *                    estimateWaterLevel method.
	 * @param pumpsActive - Integer indicating how many pumps will be opened.
	 * @return double - Minimum possible water level after one cycle.
	 */
	private double getMinLevelPerPump(double waterLevel, int pumpsActive) {
		return (waterLevel + (5 * getPumpTotalCapacity(pumpsActive))) - (5 * this.configuration.getMaximualSteamRate());
	}

	/**
	 * Helper method to return true if pump controller has opened pump.
	 * 
	 * @param pumpControllerState - Pump Controller Messages.
	 * @param pumpNo              - Integer indicating the pump number.
	 * @return boolean - True, pump open, false pump closed.
	 */
	private static boolean getPumpControllerState(Message[] pumpControllerState, int pumpNo) {
		return pumpControllerState[pumpNo].getBooleanParameter();
	}

	/**
	 * Helper Method to return if true if the pump indicates its open.
	 * 
	 * @param pumpState - Messages sent by pump.
	 * @param pumpNo    - Integer indicating the pump number.
	 * @return boolean - True if pump is open, false if pump is closed.
	 */
	private static boolean getPumpState(Message[] pumpState, int pumpNo) {
		return pumpState[pumpNo].getBooleanParameter();
	}

	/**
	 * Checks the water level reading given by the water level sensor is within
	 * acceptable limits.
	 * 
	 * @param waterLevel - Double parameter received by the level sensor.
	 * @return boolean - True, if water is within the acceptable range.
	 */
	private boolean waterLevelWithinLimits(double waterLevel) {
		// 0.8 and 1.2 used to negate false positives causes by inaccuracies due to 5
		// second difference between readings
		if (waterLevel > (this.minPossibleWaterLevel * 0.8) && waterLevel < (this.maxPossibleWaterLevel * 1.2)) {
			return true;
		}
		return false;
	}

	/**
	 * Helper method, returns number of pumps in configuration.
	 * 
	 * @return integer - Number of pumps in the configuration.
	 */
	private int getNoOfPumps() {
		return this.configuration.getNumberOfPumps();
	}

	/**
	 * Helper method, returns the minimal normal level.
	 * 
	 * @return double - Minimal normal water level.
	 */
	private double minWaterLevel() {
		return this.configuration.getMinimalNormalLevel();
	}

	/**
	 * Helper method, returns the maximal normal level.
	 * 
	 * @return double - Maximal normal water level.
	 */
	private double maxWaterLevel() {
		return this.configuration.getMaximalNormalLevel();
	}

	/**
	 * Helper method, returns the optimal water level max water level plus minimal
	 * water level divided by two.
	 * 
	 * @return double - Optimal water level.
	 */
	private double optimalWaterLevel() {
		return (this.configuration.getMaximalNormalLevel() + this.configuration.getMinimalNormalLevel()) / 2;
	}

	/**
	 * Helper method, returns the maximum safe water level.
	 * 
	 * @return double - Max water level.
	 */
	private double maxSafteyLimit() {
		return this.configuration.getMaximalLimitLevel();
	}

	/**
	 * Helper method, returns the minimum safe water level.
	 * 
	 * @return double - Minimum water level.
	 */
	private double minSafteyLimit() {
		return this.configuration.getMinimalLimitLevel();
	}
}
