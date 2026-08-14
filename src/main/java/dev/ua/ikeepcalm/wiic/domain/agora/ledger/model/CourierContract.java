package dev.ua.ikeepcalm.wiic.domain.agora.ledger.model;

import java.util.UUID;

/**
 * One row of {@code courier_contracts} — a horn a player left with the Courier Post so
 * their market purchases are delivered by a postman instead of waiting in their stash.
 *
 * <p>The horn item itself is escrowed as bytes (returned verbatim on withdrawal), and
 * {@code courierType} is the tier resolved from the depositor's permissions at deposit
 * time: undead-postmans decides tiers per player, not per horn, so it has to be captured
 * while they are online and cannot be re-derived at delivery time for an offline player.
 */
public record CourierContract(
        UUID playerUuid,
        byte[] hornItemBytes,
        String courierType,
        long depositedAt
) {}
