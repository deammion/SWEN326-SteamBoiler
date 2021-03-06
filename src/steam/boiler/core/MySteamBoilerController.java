package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

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
  
  private Boolean emptyingTank = false;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
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
			outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.NORMAL));
			this.mode = State.NORMAL;
		}
		
		if(mode == State.NORMAL) {
			normalOperationMode(levelMessage, steamMessage, pumpStateMessages, outgoing);
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
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
		//turn on all pumps to allow for faster filling of boiler, also tests all pumps are operational
		if(waterLevel < minWaterLevel()) {
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 1));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 2));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 3));
		} 
		//turns off pumps 2 and 3, once level is above minimal normal level
		if (waterLevel > minWaterLevel()) {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 1));
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 2));
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 3));
		} 
		//turns off pumps 0 and 1, once level is above optimal water level (half capacity)
		if (waterLevel >= optimalWaterLevel()){
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
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
	private void normalOperationMode(Message levelMessage, Message steamMessage, Message[] pumpStateMessages,@NonNull Mailbox outgoing) {
		//System.out.println("normal operation mode");
		int pumpsToActivate = pumpsToActivate(levelMessage.getDoubleParameter(), steamMessage.getDoubleParameter());
		int pumpsAvailable = configuration.getNumberOfPumps();
		int currentPumpsOpen = -1; //represents no pumps open, 0 would indicated 1 pump, pump no.0 is open
		for(int i = 0; i < pumpsAvailable; i++) {
			if(pumpStateMessages[i].getBooleanParameter() == true) {
				currentPumpsOpen += 1;
			}
		}
		if(currentPumpsOpen > pumpsToActivate) {
			for(int i = (pumpsAvailable - 1); i > pumpsToActivate; i--) {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
			}
		} else if (currentPumpsOpen < pumpsToActivate) {
			for(int i = 0; i <= pumpsToActivate; i++) {
				outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
			}
		}
	}
	
	/**
	 * uses the current water level, plus pump capacity minus the steam produced to determine to min/max and average values per no of pumps open
	 * stores these value in an array of doubles. checks how many pumps need to be opened to reach the optimal level, whilst also making sure it 
	 * will not go over the max 
	 * 
	 * @param waterLevel - double representative of the water level given by the level sensor
	 * @param steamLevel - double representative of the stem production given by the steam sensor
	 * @return pumpsToActivate - int, number of pumps to activate -1 indicates nil pumps to open
	 */
	private int pumpsToActivate(Double waterLevel, Double steamLevel) {
		int pumpsToActivate = -1;
		int pumpsAvaliable = configuration.getNumberOfPumps();
		
		double waterDecepency = configuration.getCapacity();
		
		double[] proximtyToOptimal = {0,0,0,0};
		double[] maxPerPumpsOpen = {0,0,0,0};
		double[] minPerPumpsOpen = {0,0,0,0};
		
		//calculate the possible range of the water levels per pump(s) activated
		for(int i = 0; i < pumpsAvaliable; i++) {
			maxPerPumpsOpen[i] = waterLevel + (5 * getPumpTotalCapacity(i)) - (5 * steamLevel);
			minPerPumpsOpen[i] = waterLevel + (5 * getPumpTotalCapacity(i)) - (5 * configuration.getMaximualSteamRate());
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
	 * Helper method used to return true capacity of pumps, useful if not all pumps have the 
	 * same capacity. i.e. half capacity etc
	 * 
	 * @param pumpNumber - which/how many of the pumps capacity is required
	 * @return pumpTotalCapacity - a double dictating the sum of the selected pumps capacity
	 */
	private double getPumpTotalCapacity(int pumpNumber) {
		int pumpTotalCapacity = 0;
		for(int i = 0; i <= pumpNumber; i++) {
			pumpTotalCapacity += configuration.getPumpCapacity(i);
		}
		return pumpTotalCapacity;
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
	
}

