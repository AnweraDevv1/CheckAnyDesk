package ru.kursk.checkanydesk;

import java.util.UUID;

final class CheckSession {
    private final UUID targetId;
    private final String targetName;
    private final UUID moderatorId;
    private final String moderatorName;
    private final long startedAt;
    private long endsAt;
    private long totalMillis;

    CheckSession(UUID uUID, String string, UUID uUID2, String string2, long l, long l2, long l3) {
        this.targetId = uUID;
        this.targetName = string;
        this.moderatorId = uUID2;
        this.moderatorName = string2;
        this.startedAt = l;
        this.endsAt = l2;
        this.totalMillis = l3;
    }

    UUID targetId() {
        return this.targetId;
    }

    String targetName() {
        return this.targetName;
    }

    UUID moderatorId() {
        return this.moderatorId;
    }

    String moderatorName() {
        return this.moderatorName;
    }

    long startedAt() {
        return this.startedAt;
    }

    long endsAt() {
        return this.endsAt;
    }

    long totalMillis() {
        return this.totalMillis;
    }

    void addTime(long l) {
        this.endsAt = Math.addExact(this.endsAt, l);
        this.totalMillis = Math.addExact(this.totalMillis, l);
    }

    void removeTime(long l) {
        this.endsAt = Math.subtractExact(this.endsAt, l);
        this.totalMillis = Math.max(1L, this.totalMillis - l);
    }
}

