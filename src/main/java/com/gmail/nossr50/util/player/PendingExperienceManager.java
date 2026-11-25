package com.gmail.nossr50.util.player;

import static com.gmail.nossr50.datatypes.experience.XPGainSource.CUSTOM;

import com.gmail.nossr50.datatypes.experience.XPGainReason;
import com.gmail.nossr50.datatypes.player.McMMOPlayer;
import com.gmail.nossr50.datatypes.skills.PrimarySkillType;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.entity.Player;

/** Holds API grants only while the corresponding online profile is loading. */
public final class PendingExperienceManager {
    public enum Mode {
        RAW, UNSHARED, NORMAL;

        public void apply(McMMOPlayer player, PrimarySkillType skill, float xp, XPGainReason reason) {
            switch (this) {
                case RAW -> player.applyXpGain(skill, xp, reason, CUSTOM);
                case UNSHARED -> player.beginUnsharedXpGain(skill, xp, reason, CUSTOM);
                case NORMAL -> player.beginXpGain(skill, xp, reason, CUSTOM);
            }
        }
    }

    public static final class LoadingSession {
        private final Player player;
        private final UUID uuid;

        private LoadingSession(Player player) {
            this.player = player;
            this.uuid = player.getUniqueId();
        }
    }

    private static final int MAGIC = 0x4D585031;
    private static final int MAX_GRANTS_PER_PLAYER = 1024;
    private final Map<UUID, PendingPlayer> pending = new HashMap<>();
    private final Path file;
    private final Path activeMarker;
    private final Logger logger;
    private final Object storageLock = new Object();
    private boolean closed;
    private long revision;
    private long savedRevision;

    private static final class PendingPlayer {
        private LoadingSession session;
        private final List<Grant> grants = new ArrayList<>();
    }

    private record Grant(PrimarySkillType skill, float xp, XPGainReason reason, Mode mode,
                         boolean failed) {
    }

    public PendingExperienceManager(Path directory, Logger logger) throws IOException {
        this.file = directory.resolve("pending-experience.bin");
        this.activeMarker = directory.resolve("pending-experience.active");
        this.logger = logger;
        load();
        if (Files.exists(activeMarker) && !pending.isEmpty()) {
            pending.values().forEach(account -> account.grants.replaceAll(grant ->
                    new Grant(grant.skill, grant.xp, grant.reason, grant.mode, true)));
            revision++;
            logger.severe("Previous pending XP shutdown was incomplete. Retained grants in "
                    + file + " require review and will not be replayed automatically.");
        }
        Files.createDirectories(directory);
        Files.writeString(activeMarker, "Pending XP session active\n");
    }

    public synchronized LoadingSession beginLoading(Player player) {
        if (closed) {
            throw new IllegalStateException("mcMMO is shutting down");
        }
        LoadingSession session = new LoadingSession(player);
        pending.computeIfAbsent(session.uuid, ignored -> new PendingPlayer()).session = session;
        return session;
    }

    public synchronized boolean isCurrent(LoadingSession session) {
        PendingPlayer account = pending.get(session.uuid);
        return !closed && account != null && account.session == session;
    }

    public boolean enqueue(Player player, PrimarySkillType skill, float xp,
            XPGainReason reason, Mode mode) {
        if (player == null || !player.isOnline()) {
            return false;
        }
        UUID uuid = player.getUniqueId();
        synchronized (this) {
            PendingPlayer account = pending.get(uuid);
            if (closed || account == null || account.session == null
                    || account.session.player != player) {
                return false;
            }
            if (account.grants.size() >= MAX_GRANTS_PER_PLAYER) {
                throw new IllegalStateException("mcMMO pending XP limit reached for " + uuid);
            }
            account.grants.add(new Grant(skill, xp, reason, mode, false));
            revision++;
            return true;
        }
    }

    public synchronized void quit(Player player) {
        PendingPlayer account = pending.get(player.getUniqueId());
        if (account != null && account.session != null && account.session.player == player) {
            account.session = null;
            removeIfEmpty(player.getUniqueId(), account);
        }
    }

    /** Called on the player's owning region after the profile has been attached and initialized. */
    public void complete(LoadingSession session, McMMOPlayer player) {
        PendingPlayer account;
        List<Grant> grants;
        synchronized (this) {
            if (!isCurrent(session)) {
                return;
            }
            account = pending.get(session.uuid);
            grants = new ArrayList<>(account.grants);
        }
        for (Grant grant : grants) {
            if (grant.failed) {
                continue;
            }
            synchronized (this) {
                if (!isCurrent(session)) {
                    return;
                }
            }
            try {
                grant.mode.apply(player, grant.skill, grant.xp, grant.reason);
                synchronized (this) {
                    account.grants.remove(grant);
                    revision++;
                }
            } catch (RuntimeException exception) {
                // An exception may follow a partial gain. Preserve it for review instead of retrying.
                synchronized (this) {
                    int index = account.grants.indexOf(grant);
                    account.grants.set(index,
                            new Grant(grant.skill, grant.xp, grant.reason, grant.mode, true));
                    revision++;
                }
                logger.log(Level.SEVERE, "Pending mcMMO XP requires review for " + session.uuid
                        + " " + grant + ". Retained in " + file, exception);
            }
        }
        synchronized (this) {
            if (isCurrent(session)) {
                account.session = null;
                removeIfEmpty(session.uuid, account);
            }
        }
    }

    private void removeIfEmpty(UUID uuid, PendingPlayer account) {
        if (account.grants.isEmpty()) {
            pending.remove(uuid);
        }
    }

    public void saveSafely() {
        try {
            save();
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Could not save pending mcMMO XP to " + file, exception);
        }
    }

    public void close() {
        synchronized (this) {
            closed = true;
            pending.values().forEach(account -> account.session = null);
        }
        try {
            save();
            Files.deleteIfExists(activeMarker);
        } catch (IOException exception) {
            logger.log(Level.SEVERE, "Pending XP shutdown incomplete. Retaining " + activeMarker,
                    exception);
        }
    }

    public void save() throws IOException {
        synchronized (storageLock) {
            byte[] snapshot;
            long snapshotRevision;
            synchronized (this) {
                if (revision == savedRevision) {
                    return;
                }
                snapshotRevision = revision;
                ByteArrayOutputStream bytes = new ByteArrayOutputStream();
                try (DataOutputStream output = new DataOutputStream(bytes)) {
                    output.writeInt(MAGIC);
                    output.writeInt(1);
                    long count = pending.values().stream().filter(p -> !p.grants.isEmpty()).count();
                    output.writeInt((int) count);
                    for (var entry : pending.entrySet()) {
                        if (entry.getValue().grants.isEmpty()) {
                            continue;
                        }
                        output.writeUTF(entry.getKey().toString());
                        output.writeInt(entry.getValue().grants.size());
                        for (Grant grant : entry.getValue().grants) {
                            output.writeUTF(grant.skill.name());
                            output.writeFloat(grant.xp);
                            output.writeUTF(grant.reason.name());
                            output.writeUTF(grant.mode.name());
                            output.writeBoolean(grant.failed);
                        }
                    }
                }
                snapshot = bytes.toByteArray();
            }
            Files.createDirectories(file.getParent());
            Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
            Files.write(temporary, snapshot);
            // Keep the old file intact if the filesystem cannot replace it atomically.
            Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING);
            synchronized (this) {
                savedRevision = snapshotRevision;
            }
        }
    }

    private void load() throws IOException {
        if (!Files.exists(file)) {
            return;
        }
        try (DataInputStream input = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(file)))) {
            if (input.readInt() != MAGIC || input.readInt() != 1) {
                throw new IOException("Invalid pending XP header in " + file);
            }
            int players = input.readInt();
            if (players < 0 || players > 1_000_000) {
                throw new IOException("Invalid pending XP player count");
            }
            for (int i = 0; i < players; i++) {
                UUID uuid = UUID.fromString(input.readUTF());
                PendingPlayer account = new PendingPlayer();
                int count = input.readInt();
                if (count < 1 || count > MAX_GRANTS_PER_PLAYER || pending.containsKey(uuid)) {
                    throw new IOException("Invalid pending XP grants for " + uuid);
                }
                for (int j = 0; j < count; j++) {
                    account.grants.add(new Grant(PrimarySkillType.valueOf(input.readUTF()),
                            input.readFloat(), XPGainReason.valueOf(input.readUTF()),
                            Mode.valueOf(input.readUTF()), input.readBoolean()));
                }
                pending.put(uuid, account);
            }
            if (input.read() != -1) {
                throw new IOException("Unexpected trailing pending XP data");
            }
        } catch (IllegalArgumentException exception) {
            throw new IOException("Invalid pending XP data in " + file, exception);
        }
    }
}
