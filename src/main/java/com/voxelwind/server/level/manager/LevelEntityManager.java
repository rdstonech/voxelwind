package com.voxelwind.server.level.manager;

import com.google.common.collect.ImmutableList;
import com.voxelwind.server.level.Level;
import com.voxelwind.server.level.entities.BaseEntity;
import com.voxelwind.server.network.mcpe.packets.McpeMoveEntity;
import com.voxelwind.server.network.mcpe.packets.McpeSetEntityMotion;
import com.voxelwind.server.network.session.PlayerSession;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;

/**
 * This manager handles entities in levels. This class is safe for concurrent use.
 */
public class LevelEntityManager {
    private static final Logger LOGGER = LogManager.getLogger(LevelEntityManager.class);

    private final List<BaseEntity> entities = new ArrayList<>();
    private final AtomicLong entityIdAllocator = new AtomicLong();
    private final Level level;

    public LevelEntityManager(Level level) {
        this.level = level;
    }

    public synchronized void register(BaseEntity entity) {
        entities.add(entity);
    }

    public synchronized void onTick() {
        List<BaseEntity> failedToTick = new ArrayList<>();
        for (BaseEntity entity : entities) {
            try {
                entity.onTick();

                if (entity.isStale()) {
                    // Need to send packets.
                    if (entity instanceof PlayerSession) {
                        // TODO: Player move packet.
                    }

                    McpeMoveEntity moveEntityPacket = new McpeMoveEntity();
                    moveEntityPacket.setEntityId(entity.getEntityId());
                    moveEntityPacket.setPosition(entity.getPosition());
                    moveEntityPacket.setRotation(entity.getRotation());
                    level.getPacketManager().queuePacketForViewers(entity, moveEntityPacket);

                    McpeSetEntityMotion motionPacket = new McpeSetEntityMotion();
                    motionPacket.getMotionList().add(new McpeSetEntityMotion.EntityMotion(entity.getEntityId(), entity.getMotion()));
                    level.getPacketManager().queuePacketForViewers(entity, motionPacket);

                    entity.setStale(false);
                }
            } catch (Exception e) {
                LOGGER.error("Unable to tick entity", e);
                failedToTick.add(entity);
            }
        }

        for (BaseEntity baseEntity : failedToTick) {
            // TODO: Despawn entities.
        }
    }

    public synchronized List<PlayerSession> getPlayers() {
        List<PlayerSession> sessions = new ArrayList<>();

        for (BaseEntity entity : entities) {
            if (entity instanceof PlayerSession) {
                sessions.add((PlayerSession) entity);
            }
        }

        return sessions;
    }

    public long allocateEntityId() {
        return entityIdAllocator.incrementAndGet();
    }

    public synchronized Collection<BaseEntity> getAllEntities() {
        return ImmutableList.copyOf(entities);
    }

    public synchronized Optional<BaseEntity> findEntityById(long id) {
        return entities.stream().filter(e -> e.getEntityId() == id).findFirst();
    }
}