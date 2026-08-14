package dev.ua.ikeepcalm.wiic.domain.agora.plots.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/**
 * One row of the {@code plots} table — who currently rents a {@link PlotRegion} and
 * until when. Rows exist only while a plot is rented; eviction deletes them, so a
 * missing row means "available".
 */
public record PlotRental(
        String plotId,
        UUID renterUuid,
        String renterName,
        long rentedAt,
        long paidUntil,
        @Nullable Integer vendorNpcId
) {

    /** Rent is still covered — the renter has full build/container rights. */
    public boolean isPaid(long now) {
        return now < paidUntil;
    }

    /** Rent has lapsed but the grace period hasn't run out; rights are kept, eviction pends. */
    public boolean isInGrace(long now, long graceMs) {
        return !isPaid(now) && now <= paidUntil + graceMs;
    }

    /** Grace is over — the upkeep task evicts on its next pass. */
    public boolean isEvictable(long now, long graceMs) {
        return now > paidUntil + graceMs;
    }
}
