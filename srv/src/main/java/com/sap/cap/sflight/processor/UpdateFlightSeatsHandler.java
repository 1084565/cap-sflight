package com.sap.cap.sflight.processor;

import cds.gen.travelservice.Booking;
import cds.gen.travelservice.Booking_;
import cds.gen.travelservice.Travel;
import cds.gen.travelservice.TravelService_;
import cds.gen.travelservice.Travel_;
import com.sap.cds.CdsDiffProcessor;
import com.sap.cds.CdsDiffProcessor.DiffVisitor;
import com.sap.cds.impl.diff.DiffProcessor;
import com.sap.cds.ql.CQL;
import com.sap.cds.ql.Select;
import com.sap.cds.ql.Update;
import com.sap.cds.ql.cqn.CqnAnalyzer;
import com.sap.cds.ql.cqn.CqnDelete;
import com.sap.cds.ql.cqn.CqnUpdate;
import com.sap.cds.ql.cqn.Path;
import com.sap.cds.reflect.CdsAssociationType;
import com.sap.cds.reflect.CdsElement;
import com.sap.cds.reflect.CdsStructuredType;
import com.sap.cds.services.EventContext;
import com.sap.cds.services.cds.CdsDeleteEventContext;
import com.sap.cds.services.cds.CqnService;
import com.sap.cds.services.handler.EventHandler;
import com.sap.cds.services.handler.annotations.Before;
import com.sap.cds.services.handler.annotations.ServiceName;
import com.sap.cds.services.persistence.PersistenceService;
import com.sap.cds.services.utils.StringUtils;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import static cds.gen.travelservice.TravelService_.FLIGHT;

@Component
@ServiceName(TravelService_.CDS_NAME)
public class UpdateFlightSeatsHandler implements EventHandler {

    public static final String TO_BOOKING = "to_Booking";
    public static final String CONNECTION_ID = "ConnectionID";
    public static final String OCCUPIED_SEATS = "occupiedSeats";

    private final PersistenceService persistenceService;

    public UpdateFlightSeatsHandler(PersistenceService persistenceService) {
        this.persistenceService = persistenceService;
    }

    enum Status {
        ADDED,
        DELETED
    }

    @Before(event = { "CREATE", "UPDATE", "DELETE" }, entity = Travel_.CDS_NAME)
    public void updateSeatsDiffProc(EventContext context, List<Travel> travels) {

        Map<Status, List<String>> modifications = new EnumMap<>(Status.class);

        switch (context.getEvent()) {

            case CqnService.EVENT_CREATE:
                modifications.putIfAbsent(Status.ADDED, new ArrayList<>());
                for (Travel travel : travels) {
                    for (Booking booking : travel.toBooking()) {
                        String connectionId = booking.connectionID();
                        if (connectionId != null) {
                            modifications.get(Status.ADDED).add(connectionId);
                        }
                    }
                }
                break;

            case CqnService.EVENT_DELETE:
                modifications.putIfAbsent(Status.DELETED, new ArrayList<>());
                for (Booking booking : getOldStateTravel(getTravelUuidFromDeleteCqn(context)).toBooking()) {
                    String connectionId = booking.connectionID();
                    if (connectionId != null) {
                        modifications.get(Status.DELETED).add(connectionId);
                    }
                }
                break;

            case CqnService.EVENT_UPDATE:
                for (Travel travel : travels) {
                    handleUpdatedTravelWithDiffProcessor(context, travel, modifications);
                }
                break;

            default:
                throw new IllegalStateException("Unexpected value: " + context.getEvent());

        }

        updateSeatsOnFlights(modifications);

    }

    private void handleUpdatedTravelWithDiffProcessor(EventContext context, Travel newState,
            Map<Status, List<String>> modifications) {


        if(StringUtils.isEmpty(newState.travelUUID())) {
            // this update does not even have a TravelUUID. No reason to continue.
            return;
        }

        CdsDiffProcessor diffProcessor = DiffProcessor.create();
        Travel oldState = getOldStateTravel(newState.travelUUID());
        diffProcessor.add(
                (path, cdsElement, cdsType) -> {
                    if (cdsElement == null) {
                        return false;
                    }
                    // Filter for booking additions/removals (path.target() is the parent Travel
                    // when the visitor is invoked for add/remove of Booking entries)
                    if (path.target().type().getQualifiedName().equals(Travel_.CDS_NAME)
                            && cdsElement.getName().equals(TO_BOOKING)) {
                        return true;
                    }
                    // Filter for ConnectionID changes on Booking. ConnectionID is the (managed)
                    // foreign key of Booking.to_Flight — CAP Java 5 no longer transports the
                    // nested to_Flight sub-map into Before-UPDATE handlers, so we key off the FK.
                    return path.target().type().getQualifiedName().equals(Booking_.CDS_NAME)
                            && cdsElement.getName().equals(CONNECTION_ID);
                },
                new BookingDiffVisitor(modifications));

        diffProcessor.process(newState, oldState, context.getTarget());
    }

    private Travel getOldStateTravel(String travelUUID) {
        Select<Travel_> query = Select.from(TravelService_.TRAVEL)
                .where(t -> t.TravelUUID().eq(travelUUID).and(t.IsActiveEntity().eq(true)))
                .columns(Travel_::TravelUUID, t -> t.to_Booking()
                        .expand(Booking_::BookingUUID, Booking_::ConnectionID));
        return this.persistenceService.run(query).single(Travel.class);
    }

    private String getTravelUuidFromDeleteCqn(EventContext context) {
        CqnAnalyzer cqnAnalyzer = CqnAnalyzer.create(context.getModel());
        CqnDelete deleteStatement = ((CdsDeleteEventContext) context).getCqn();
        return (String) cqnAnalyzer.analyze(deleteStatement).targetKeyValues().get("TravelUUID");
    }

    void updateSeatsOnFlights(Map<Status, List<String>> connectionIdsByStatus) {

        for (Map.Entry<Status, List<String>> entry : connectionIdsByStatus.entrySet()) {

            Status status = entry.getKey();
            List<String> connectionIds = entry.getValue();
            if (status == Status.ADDED) {
                for (String connectionId : connectionIds) {
                    CqnUpdate addSeats = Update.entity(FLIGHT).where(w -> w.ConnectionID().eq(connectionId))
                            .set(OCCUPIED_SEATS, CQL.get(OCCUPIED_SEATS).plus(1));
                    this.persistenceService.run(addSeats);
                }
            }

            if (status == Status.DELETED) {
                for (String connectionId : connectionIds) {
                    CqnUpdate deleteSeats = Update.entity(FLIGHT).where(w -> w.ConnectionID().eq(connectionId))
                            .set(OCCUPIED_SEATS, CQL.get(OCCUPIED_SEATS).minus(1));
                    this.persistenceService.run(deleteSeats);
                }
            }
        }
    }

    private static class BookingDiffVisitor implements DiffVisitor {
        private final Map<Status, List<String>> modifications;

        public BookingDiffVisitor(Map<Status, List<String>> modifications) {
            this.modifications = modifications;
        }

        @Override
        public void changed(Path newPath, Path oldPath, CdsElement element, Object newValue, Object oldValue) {

            // ConnectionID is the (managed) foreign key of Booking.to_Flight. When it changes
            // to a different non-null value, the booking now refers to a different flight:
            // decrement the old flight's seats and increment the new one's.
            // A null newValue means the field wasn't part of the update payload, not that
            // the flight was cleared.
            if (newPath.target().type().getQualifiedName().equals(Booking_.CDS_NAME)
                    && element.getName().equals(CONNECTION_ID)
                    && newValue != null && oldValue != null) {
                modifications.computeIfAbsent(Status.DELETED, k -> new ArrayList<>()).add((String) oldValue);
                modifications.computeIfAbsent(Status.ADDED, k -> new ArrayList<>()).add((String) newValue);
            }
        }

        @Override
        public void added(Path newPath, Path oldPath, CdsElement association, Map<String, Object> newValue) {
            CdsStructuredType target = association != null ? association.getType().as(CdsAssociationType.class).getTarget() : newPath.target().type();

            if (target.getQualifiedName().equals(Booking_.CDS_NAME)
                    || Objects.requireNonNull(association).getName().equals(TO_BOOKING)) {
                Object connectionId = newValue.get(CONNECTION_ID);
                if (connectionId != null) {
                    modifications.computeIfAbsent(Status.ADDED, k -> new ArrayList<>()).add((String) connectionId);
                }
            }
        }

        @Override
        public void removed(Path newPath, Path oldPath, CdsElement association, Map<String, Object> oldValue) {
            CdsStructuredType target = association != null ? association.getType().as(CdsAssociationType.class).getTarget() : newPath.target().type();
            if (target.getQualifiedName().equals(Booking_.CDS_NAME)
                    || Objects.requireNonNull(association).getName().equals(TO_BOOKING)) {
                Object connectionId = oldValue.get(CONNECTION_ID);
                if (connectionId != null) {
                    modifications.computeIfAbsent(Status.DELETED, k -> new ArrayList<>()).add((String) connectionId);
                }
            }
        }
    }
}
