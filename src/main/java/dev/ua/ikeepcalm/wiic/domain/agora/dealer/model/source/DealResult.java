package dev.ua.ikeepcalm.wiic.domain.agora.dealer.model.source;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum DealResult {
    DELIVERED("dealer-bought", "<green>The dealer wraps it up and slips it into your pack."),
    STASHED("dealer-bought-stash", "<green>Paid. The dealer left it with the Ledger Keeper — collect it from your stash."),
    CLOSED("dealer-closed", "<gray>The dealer's shutters are down."),
    SOLD_OUT("dealer-sold-out", "<gray>\"Gone. Someone was quicker. Come back after the next shipment.\""),
    LIMIT_REACHED("dealer-limit", "<red>\"That's enough for one day, friend.\""),
    BUSY("in-progress", "<red>Finish your current dealing first."),
    INSUFFICIENT_FUNDS("insufficient-funds", "<red>Your purse is too light for this."),
    ERROR("market-error", "<red>The market ledgers are in disarray. Try again later.");

    private final String messageKey;
    private final String fallback;

    public boolean success() {
        return this == DELIVERED || this == STASHED;
    }
}
