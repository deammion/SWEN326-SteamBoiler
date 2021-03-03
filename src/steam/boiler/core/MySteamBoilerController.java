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
		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			this.mode = State.EMERGENCY_STOP;
		}
		//pre initialisation, all pumps turned on to fill boiler
		if (levelMessage.getDoubleParameter() == 0) {
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 0));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 1));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 2));
			outgoing.send(new Message(MessageKind.OPEN_PUMP_n, 3));
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
		}
		
		if(levelMessage.getDoubleParameter() >= maxWaterLevel()) {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 1));
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 2));
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 3));
			this.mode = State.READY;	
		}
		
		if(mode == State.READY) {
			outgoing.send(new Message(MessageKind.PROGRAM_READY));
			outgoing.send(new Message(MessageKind.MODE_m,Mailbox.Mode.NORMAL));
		}
		//normal mode, control pumps need to keep water level within tolerance
		
		if(levelMessage.getDoubleParameter() <= minWaterLevel()) {
			double totalPumpCapacity = 0;
			int i = 0;
			while(steamMessage.getDoubleParameter() >= totalPumpCapacity) {
				outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
				totalPumpCapacity += configuration.getPumpCapacity(i);
				i++;
			}
		}

		// FIXME: this is where the main implementation stems from

		// NOTE: this is an example message send to illustrate the syntax
		//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
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
	
	private Double minWaterLevel() {
		return configuration.getMinimalNormalLevel();
	}
	
	private Double maxWaterLevel() {
		return configuration.getMaximalNormalLevel();
	}
	
	private Double normalWaterLevel() {
		return configuration.getCapacity()/2;
	}
	
}
/**
 * TODO method to monitor pumps, pump controller, steam outlet, are they within expected range relative to water level
 * 
 * TODO write code for clock
 * 
 * TODO maybe, back up controller for pumps
 * 
 * TODO method to return actual water level, check against expected level. create offset if outside expected level
 * 
 * TODO method to return pump state, pump rate
 * 
 * TODO method to return pump controller state
 * 
 * TODO method to return steam output
 * 
 */
