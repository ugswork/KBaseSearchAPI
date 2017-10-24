package kbasesearchengine.events.storage;

import java.util.List;

import com.google.common.base.Optional;

import kbasesearchengine.events.StatusEvent;
import kbasesearchengine.events.StatusEventID;
import kbasesearchengine.events.StatusEventProcessingState;
import kbasesearchengine.events.StoredStatusEvent;
import kbasesearchengine.events.exceptions.RetriableIndexingException;

/** A storage system for status events generated by an external service.
 * @author gaprice@lbl.gov
 *
 */
public interface StatusEventStorage {

    /** Store a new event.
     * @param newEvent the event.
     * @param state the current processing state of the event.
     * @return a stored status event.
     * @throws RetriableIndexingException if an error occurs while storing the event.
     */
    StoredStatusEvent store(StatusEvent newEvent, StatusEventProcessingState state)
            throws RetriableIndexingException;

    /** Get an event by its ID.
     * @param id the id.
     * @return the event or absent if the id does not exist in the storage system.
     * @throws RetriableIndexingException if an error occurs while getting the event.
     */
    Optional<StoredStatusEvent> get(StatusEventID id) throws RetriableIndexingException;

    /** Get list of events, by processing state, ordered by the event timestamp such that the
     * events with the earliest timestamp are first in the list.
     * @param state the processing state of the events to be returned.
     * @param limit the maximum number of events to return. If < 1 or > 1000 is set to 1000.
     * @return the list of events.
     * @throws RetriableIndexingException if an error occurs while getting the events.
     */
    List<StoredStatusEvent> get(StatusEventProcessingState state, int limit)
            throws RetriableIndexingException;

    /** Mark an event with a processing state.
     * @param event the event to modify.
     * @param state the processing state to set on the event.
     * @return the event updated with the new state.
     * @throws RetriableIndexingException if an error occurs while setting the state.
     */
    Optional<StoredStatusEvent> setProcessingState(
            StoredStatusEvent event,
            StatusEventProcessingState state)
            throws RetriableIndexingException;

}