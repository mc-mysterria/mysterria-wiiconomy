package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model;

import org.jetbrains.annotations.Nullable;

import java.util.UUID;

/** One row of {@code ledger_entries} — a seller's proceeds from a single sale, claimable at the Banker. */
public record LedgerEntry(
        UUID id,
        UUID ownerUuid,
        long gross,
        long tax,
        long net,
        @Nullable UUID sourceListing,
        long createdAt
) {}
