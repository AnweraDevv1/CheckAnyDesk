package ru.kursk.checkanydesk.api;

import java.util.UUID;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public final class CheckResultEvent
extends Event {
    private static final HandlerList HANDLERS = new HandlerList();
    private final UUID targetId;
    private final String targetName;
    private final UUID moderatorId;
    private final String moderatorName;
    private final Result result;

    public CheckResultEvent(UUID uUID, String string, UUID uUID2, String string2, Result result) {
        this.targetId = uUID;
        this.targetName = string;
        this.moderatorId = uUID2;
        this.moderatorName = string2;
        this.result = result;
    }

    public UUID getTargetId() {
        return this.targetId;
    }

    public String getTargetName() {
        return this.targetName;
    }

    public UUID getModeratorId() {
        return this.moderatorId;
    }

    public String getModeratorName() {
        return this.moderatorName;
    }

    public Result getResult() {
        return this.result;
    }

    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }

    public static enum Result {
        PASSED,
        CHEATS_BANNED,
        REFUSAL_BANNED;

    }
}

