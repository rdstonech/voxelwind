package com.voxelwind.server.network.session;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeType;
import com.flowpowered.math.vector.Vector2i;
import com.flowpowered.math.vector.Vector3f;
import com.google.common.base.Preconditions;
import com.google.common.base.Verify;
import com.google.common.base.VerifyException;
import com.spotify.futures.CompletableFutures;
import com.voxelwind.api.game.entities.Entity;
import com.voxelwind.api.game.entities.components.Health;
import com.voxelwind.api.game.entities.components.system.System;
import com.voxelwind.api.game.entities.components.system.SystemRunner;
import com.voxelwind.api.game.entities.misc.DroppedItem;
import com.voxelwind.api.game.inventories.Inventory;
import com.voxelwind.api.game.inventories.OpenableInventory;
import com.voxelwind.api.game.inventories.PlayerInventory;
import com.voxelwind.api.game.item.ItemStack;
import com.voxelwind.api.game.level.Chunk;
import com.voxelwind.api.game.level.Level;
import com.voxelwind.api.game.level.block.Block;
import com.voxelwind.api.game.level.block.BlockType;
import com.voxelwind.api.game.level.block.BlockTypes;
import com.voxelwind.api.game.util.TextFormat;
import com.voxelwind.api.server.Player;
import com.voxelwind.api.server.Skin;
import com.voxelwind.api.server.command.CommandException;
import com.voxelwind.api.server.command.CommandNotFoundException;
import com.voxelwind.api.server.event.block.BlockReplaceEvent;
import com.voxelwind.api.server.event.player.PlayerJoinEvent;
import com.voxelwind.api.server.event.player.PlayerSpawnEvent;
import com.voxelwind.api.server.player.GameMode;
import com.voxelwind.api.server.player.PlayerMessageDisplayType;
import com.voxelwind.api.server.player.PopupMessage;
import com.voxelwind.api.server.player.TranslatedMessage;
import com.voxelwind.api.game.util.data.BlockFace;
import com.voxelwind.server.VoxelwindServer;
import com.voxelwind.server.command.VoxelwindCommandManager;
import com.voxelwind.server.game.entities.misc.VoxelwindDroppedItem;
import com.voxelwind.server.game.inventories.*;
import com.voxelwind.server.game.level.block.BlockBehavior;
import com.voxelwind.server.game.level.block.BlockBehaviors;
import com.voxelwind.server.game.level.block.behaviors.BehaviorUtils;
import com.voxelwind.server.game.level.VoxelwindLevel;
import com.voxelwind.server.game.level.block.BasicBlockState;
import com.voxelwind.server.game.level.chunk.VoxelwindChunk;
import com.voxelwind.server.game.entities.*;
import com.voxelwind.server.game.level.util.Attribute;
import com.voxelwind.server.game.level.util.BoundingBox;
import com.voxelwind.server.network.NetworkPackage;
import com.voxelwind.server.network.raknet.handler.NetworkPacketHandler;
import com.voxelwind.server.network.mcpe.packets.*;
import com.voxelwind.api.util.Rotation;
import gnu.trove.set.TLongSet;
import gnu.trove.set.hash.TLongHashSet;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static com.voxelwind.server.network.mcpe.packets.McpePlayerAction.*;

public class PlayerSession extends LivingEntity implements Player, InventoryObserver {
    private static final int REQUIRED_TO_SPAWN = 56;
    private static final Logger LOGGER = LogManager.getLogger(PlayerSession.class);

    private final McpeSession session;
    private final Set<Vector2i> sentChunks = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private final TLongSet isViewing = new TLongHashSet();
    private GameMode gameMode = GameMode.SURVIVAL;
    private boolean spawned = false;
    private int viewDistance = 5;
    private final AtomicInteger windowIdGenerator = new AtomicInteger();
    private Inventory openedInventory;
    private byte openInventoryId = -1;
    private boolean hasMoved = false;
    private final VoxelwindBasePlayerInventory playerInventory = new VoxelwindBasePlayerInventory(this);
    private final VoxelwindServer vwServer;
    private final Set<UUID> playersSentForList = new HashSet<>();
    private float baseSpeed = 0.1f;

    public PlayerSession(McpeSession session, VoxelwindLevel level) {
        super(EntityTypeData.PLAYER, level, level.getSpawnLocation(), session.getServer(), 20);
        this.session = session;
        this.vwServer = session.getServer();
    }

    @Override
    public NetworkPackage createAddEntityPacket() {
        McpeAddPlayer addPlayer = new McpeAddPlayer();
        addPlayer.setEntityId(getEntityId());
        addPlayer.setVelocity(getMotion());
        addPlayer.setPosition(getPosition());
        addPlayer.setHeld(playerInventory.getStackInHand().orElse(null));
        addPlayer.setUsername(getMcpeSession().getAuthenticationProfile().getDisplayName());
        addPlayer.setUuid(getMcpeSession().getAuthenticationProfile().getIdentity());
        addPlayer.getMetadata().putAll(getMetadata());
        return addPlayer;
    }

    private void pickupAdjacent() {
        BoundingBox box = getBoundingBox().grow(0.5f, 0.25f, 0.5f);
        for (BaseEntity entity : getLevel().getEntityManager().getEntitiesInBounds(box)) {
            if (entity instanceof DroppedItem) {
                // TODO: Check pickup delay and fire events.
                if (((DroppedItem) entity).canPickup() && getInventory().addItem(((VoxelwindDroppedItem) entity).getItemStack())) {
                    McpeTakeItem packetBroadcast = new McpeTakeItem();
                    packetBroadcast.setItemEntityId(entity.getEntityId());
                    packetBroadcast.setPlayerEntityId(getEntityId());
                    getLevel().getPacketManager().queuePacketForViewers(this, packetBroadcast);

                    McpeTakeItem packetSelf = new McpeTakeItem();
                    packetSelf.setItemEntityId(entity.getEntityId());
                    packetSelf.setPlayerEntityId(0);
                    session.addToSendQueue(packetSelf);

                    entity.remove();
                }
            }
        }
    }

    @Override
    protected void setPosition(Vector3f position) {
        super.setPosition(position);
        hasMoved = true;
    }

    @Override
    public void setRotation(@Nonnull Rotation rotation) {
        super.setRotation(rotation);
        hasMoved = true;
    }

    @Override
    public void remove() {
        throw new UnsupportedOperationException("Do not use remove() on player sessions. Use disconnect() instead.");
    }

    void removeInternal() {
        super.remove();
    }

    @Override
    public void setHealth(int health) {
        super.setHealth(health);

        if (spawned) {
            McpeSetHealth setHealthPacket = new McpeSetHealth();
            setHealthPacket.setHealth(getHealth());
            session.addToSendQueue(setHealthPacket);
        }
    }

    @Override
    public void setMaximumHealth(int maximumHealth) {
        super.setMaximumHealth(maximumHealth);
        sendAttributes();
    }

    @Override
    protected void doDeath() {
        McpeEntityEvent event = new McpeEntityEvent();
        event.setEntityId(getEntityId());
        event.setEvent((byte) 3);
        getLevel().getPacketManager().queuePacketForViewers(this, event);

        sendAttributes(); // this will trigger client-side death
    }

    private void sendAttributes() {
        Attribute health = new Attribute("minecraft:health", 0f, getMaximumHealth(), Math.max(0, getHealth()), getMaximumHealth());
        Attribute hunger = new Attribute("minecraft:player.hunger", 0f, 20f, 20f, 20f); // TODO: Implement hunger
        float effectiveSpeed = sprinting ? (float) Math.min(0.5f, baseSpeed * 1.3) : baseSpeed;
        Attribute speed = new Attribute("minecraft:movement", 0, 0.5f, effectiveSpeed, 0.1f);
        // TODO: Implement levels, movement speed, and absorption.

        McpeUpdateAttributes packet = new McpeUpdateAttributes();
        packet.getAttributes().add(health);
        packet.getAttributes().add(hunger);
        packet.getAttributes().add(speed);
        session.addToSendQueue(packet);
    }

    private void sendMovePlayerPacket() {
        McpeMovePlayer movePlayerPacket = new McpeMovePlayer();
        movePlayerPacket.setEntityId(getEntityId());
        movePlayerPacket.setPosition(getGamePosition());
        movePlayerPacket.setRotation(getRotation());
        movePlayerPacket.setMode((byte) (isTeleported() ? 1 : 0));
        movePlayerPacket.setOnGround(isOnGround());
        session.addToSendQueue(movePlayerPacket);
    }

    public void doInitialSpawn() {
        // Fire PlayerSpawnEvent.
        // TODO: Fill this in of known player data.
        PlayerSpawnEvent event = new PlayerSpawnEvent(this, getLevel().getSpawnLocation(), getLevel(), Rotation.ZERO);
        session.getServer().getEventManager().fire(event);

        if (getLevel() != event.getSpawnLevel()) {
            getLevel().getEntityManager().unregister(this);
            ((VoxelwindLevel) event.getSpawnLevel()).getEntityManager().register(this);
            setEntityId(((VoxelwindLevel) event.getSpawnLevel()).getEntityManager().allocateEntityId());
        }
        setPosition(event.getSpawnLocation());
        setRotation(event.getRotation());
        hasMoved = false; // don't send duplicated packets

        // TODO: Proper inventory links
        for (int i = 0; i < 9; i++) {
            playerInventory.setHotbarLink(i, i);
        }

        McpeSetTime setTime = new McpeSetTime();
        setTime.setTime(getLevel().getTime());
        setTime.setRunning(true);
        session.addToSendQueue(setTime);

        // Send packets to spawn the player.
        McpeStartGame startGame = new McpeStartGame();
        startGame.setSeed(-1);
        startGame.setDimension((byte) 0);
        startGame.setGenerator(1);
        startGame.setGamemode(gameMode.ordinal());
        startGame.setEntityId(getEntityId());
        startGame.setSpawn(getGamePosition());
        startGame.setWorldSpawn(getLevel().getSpawnLocation().toInt());
        startGame.setWorldName(getLevel().getName());
        startGame.setLevelId("SECRET");
        startGame.setEnableCommands(true);
        session.addToSendQueue(startGame);

        session.addToSendQueue(setTime);

        McpeAdventureSettings settings = new McpeAdventureSettings();
        settings.setPlayerPermissions(3);
        session.addToSendQueue(settings);

        McpeSetSpawnPosition spawnPosition = new McpeSetSpawnPosition();
        spawnPosition.setPosition(getLevel().getSpawnLocation().toInt());
        session.addToSendQueue(spawnPosition);

        sendMovePlayerPacket();
    }

    public McpeSession getMcpeSession() {
        return session;
    }

    NetworkPacketHandler getPacketHandler() {
        return new PlayerSessionNetworkPacketHandler();
    }

    private CompletableFuture<List<Chunk>> getChunksForRadius(int radius) {
        // Get current player's position in chunk coordinates.
        int chunkX = getPosition().getFloorX() >> 4;
        int chunkZ = getPosition().getFloorZ() >> 4;

        // Now get and send chunk data.
        Set<Vector2i> chunksForRadius = new HashSet<>();
        List<CompletableFuture<Chunk>> completableFutures = new ArrayList<>();

        for (int x = -radius; x <= radius; x++) {
            for (int z = -radius; z <= radius; z++) {
                int newChunkX = chunkX + x, newChunkZ = chunkZ + z;
                Vector2i chunkCoords = new Vector2i(newChunkX, newChunkZ);
                chunksForRadius.add(chunkCoords);

                if (!sentChunks.add(chunkCoords)) {
                    // Already sent, don't need to resend.
                    continue;
                }

                completableFutures.add(getLevel().getChunk(newChunkX, newChunkZ));
            }
        }

        sentChunks.retainAll(chunksForRadius);

        return CompletableFutures.allAsList(completableFutures);
    }

    @Override
    public void disconnect(@Nonnull String reason) {
        session.disconnect(reason);
    }

    @Override
    public void sendMessage(@Nonnull String message) {
        Preconditions.checkNotNull(message, "message");
        McpeText text = new McpeText();
        text.setType(McpeText.TextType.RAW);
        text.setMessage(message);
        session.addToSendQueue(text);
    }

    public void updateViewableEntities() {
        synchronized (isViewing) {
            Collection<BaseEntity> inView = getLevel().getEntityManager().getEntitiesInDistance(getPosition(), 64);
            TLongSet mustRemove = new TLongHashSet();
            Collection<BaseEntity> mustAdd = new ArrayList<>();

            isViewing.forEach(id -> {
                Optional<BaseEntity> optional = getLevel().getEntityManager().findEntityById(id);
                if (optional.isPresent()) {
                    if (!inView.contains(optional.get())) {
                        mustRemove.add(id);
                    }
                } else {
                    mustRemove.add(id);
                }
                return true;
            });

            for (BaseEntity entity : inView) {
                if (entity.getEntityId() == getEntityId()) {
                    continue;
                }

                // Check if user has loaded the chunk, otherwise the client will crash
                Vector2i chunkVector = new Vector2i(entity.getPosition().getFloorX() >> 4, entity.getPosition().getFloorZ() >> 4);
                if (sentChunks.contains(chunkVector) && isViewing.add(entity.getEntityId())) {
                    mustAdd.add(entity);
                }
            }

            isViewing.removeAll(mustRemove);

            mustRemove.forEach(id -> {
                McpeRemoveEntity entity = new McpeRemoveEntity();
                entity.setEntityId(id);
                session.addToSendQueue(entity);
                return true;
            });

            for (BaseEntity entity : mustAdd) {
                session.addToSendQueue(entity.createAddEntityPacket());
            }
        }
    }

    @Nonnull
    @Override
    public UUID getUniqueId() {
        return session.getAuthenticationProfile().getIdentity();
    }

    @Override
    public boolean isXboxAuthenticated() {
        return session.getAuthenticationProfile().getXuid() != null;
    }

    @Nonnull
    @Override
    public OptionalLong getXuid() {
        return session.getAuthenticationProfile().getXuid() == null ? OptionalLong.empty() :
                OptionalLong.of(session.getAuthenticationProfile().getXuid());
    }

    @Nonnull
    @Override
    public String getName() {
        return session.getAuthenticationProfile().getDisplayName();
    }

    @Nonnull
    @Override
    public Optional<InetSocketAddress> getRemoteAddress() {
        return session.getRemoteAddress();
    }

    private CompletableFuture<List<Chunk>> sendNewChunks() {
        return getChunksForRadius(viewDistance).whenComplete((chunks, throwable) -> {
            if (throwable != null) {
                LOGGER.error("Unable to load chunks for " + getMcpeSession().getAuthenticationProfile().getDisplayName(), throwable);
                disconnect("Internal server error");
                return;
            }

            // Sort by whichever chunks are closest to the player for smoother loading
            Vector3f currentPosition = getPosition();
            int currentChunkX = currentPosition.getFloorX() >> 4;
            int currentChunkZ = currentPosition.getFloorZ() >> 4;
            chunks.sort(new AroundPointComparator(currentChunkX, currentChunkZ));

            for (Chunk chunk : chunks) {
                session.sendImmediatePackage(((VoxelwindChunk) chunk).getChunkDataPacket());
            }
        });
    }

    @Override
    public Skin getSkin() {
        return new Skin(session.getClientData().getSkinId(), session.getClientData().getSkinData());
    }

    @Nonnull
    @Override
    public GameMode getGameMode() {
        return gameMode;
    }

    @Override
    public void setGameMode(@Nonnull GameMode mode) {
        GameMode oldGameMode = gameMode;
        gameMode = Preconditions.checkNotNull(mode, "mode");

        if (oldGameMode != gameMode && spawned) {
            McpeSetPlayerGameMode packet = new McpeSetPlayerGameMode();
            packet.setGamemode(mode.ordinal());
            session.addToSendQueue(packet);
        }
    }

    @Override
    public void sendMessage(@Nonnull String message, @Nonnull PlayerMessageDisplayType type) {
        Preconditions.checkNotNull(message, "message");
        Preconditions.checkNotNull(type, "type");
        McpeText text = new McpeText();
        switch (type) {
            case CHAT:
                text.setType(McpeText.TextType.RAW);
                break;
            case TIP:
                text.setType(McpeText.TextType.TIP);
                break;
            case POPUP:
                text.setType(McpeText.TextType.POPUP);
                text.setSource(""); // TODO: Is it worth adding a caption for this?
                break;
        }
        text.setMessage(message);
        session.addToSendQueue(text);
    }

    @Override
    public void sendTranslatedMessage(@Nonnull TranslatedMessage message) {
        Preconditions.checkNotNull(message, "message");
        McpeText text = new McpeText();
        text.setType(McpeText.TextType.TRANSLATE);
        text.setTranslatedMessage(message);
        session.addToSendQueue(text);
    }

    @Override
    public void sendPopupMessage(@Nonnull PopupMessage message) {
        Preconditions.checkNotNull(message, "message");
        McpeText text = new McpeText();
        text.setType(McpeText.TextType.POPUP);
        text.setSource(message.getCaption());
        text.setMessage(message.getMessage());
        session.addToSendQueue(text);
    }

    @Override
    public PlayerInventory getInventory() {
        return playerInventory;
    }

    @Override
    public Optional<Inventory> getOpenedInventory() {
        return Optional.ofNullable(openedInventory);
    }

    @Override
    public void openInventory(Inventory inventory) {
        Preconditions.checkNotNull(inventory, "inventory");
        Preconditions.checkArgument(inventory instanceof VoxelwindBaseOpenableInventory, "inventory is not a valid type that can be opened");
        Preconditions.checkState(openedInventory == null, "inventory already opened");

        VoxelwindInventoryType internalType = VoxelwindInventoryType.fromApi(inventory.getInventoryType());
        byte windowId = internalType.getWindowId(this);
        openedInventory = inventory;
        openInventoryId = windowId;

        McpeContainerOpen openPacket = new McpeContainerOpen();
        openPacket.setWindowId(windowId);
        openPacket.setSlotCount((short) inventory.getInventoryType().getInventorySize());
        openPacket.setPosition(((OpenableInventory) inventory).getPosition());
        openPacket.setType(internalType.getType());
        session.addToSendQueue(openPacket);

        McpeContainerSetContents contents = new McpeContainerSetContents();
        contents.setWindowId(windowId);
        contents.setStacks(inventory.getAllContents());
        McpeBatch contentsBatch = new McpeBatch();
        contentsBatch.getPackages().add(contents);
        session.addToSendQueue(contentsBatch);

        ((VoxelwindBaseInventory) openedInventory).getObserverList().add(this);
    }

    @Override
    public void closeInventory() {
        Preconditions.checkState(openedInventory != null, "inventory not opened");
        McpeContainerClose close = new McpeContainerClose();
        close.setWindowId(openInventoryId);
        session.addToSendQueue(close);

        ((VoxelwindBaseInventory) openedInventory).getObserverList().remove(this);
        openedInventory = null;
        openInventoryId = -1;
    }

    public byte getNextWindowId() {
        return (byte) (1 + (windowIdGenerator.incrementAndGet() % 2));
    }

    public boolean isChunkInView(int x, int z) {
        return sentChunks.contains(new Vector2i(x, z));
    }

    @Override
    public void onInventoryChange(int slot, @Nullable ItemStack oldItem, @Nullable ItemStack newItem, VoxelwindBaseInventory inventory, @Nullable PlayerSession session) {
        byte windowId;
        if (inventory == openedInventory) {
            windowId = openInventoryId;
        } else if (inventory instanceof PlayerInventory) {
            windowId = 0x00;
        } else {
            return;
        }

        if (session != this) {
            McpeContainerSetSlot packet = new McpeContainerSetSlot();
            packet.setSlot((short) slot);
            packet.setStack(newItem);
            packet.setWindowId(windowId);
            this.session.addToSendQueue(packet);
        }
    }

    @Override
    public void onInventoryContentsReplacement(ItemStack[] newItems, VoxelwindBaseInventory inventory) {
        byte windowId;
        if (inventory == openedInventory) {
            windowId = openInventoryId;
        } else if (inventory instanceof PlayerInventory) {
            windowId = 0x00;
        } else {
            return;
        }

        McpeContainerSetContents packet = new McpeContainerSetContents();
        packet.setWindowId(windowId);
        packet.setStacks(newItems);
        McpeBatch contentsBatch = new McpeBatch();
        contentsBatch.getPackages().add(packet);
        session.addToSendQueue(contentsBatch);
    }

    private void sendPlayerInventory() {
        McpeContainerSetContents contents = new McpeContainerSetContents();
        contents.setWindowId((byte) 0x00);
        // Because MCPE is stupid, we have to add 9 more slots. The rest will be filled in as air.
        contents.setStacks(Arrays.copyOf(playerInventory.getAllContents(), playerInventory.getInventoryType().getInventorySize() + 9));
        // Populate hotbar links.
        contents.setHotbarData(playerInventory.getHotbarLinks());
        McpeBatch contentsBatch = new McpeBatch();
        contentsBatch.getPackages().add(contents);
        session.sendImmediatePackage(contentsBatch);
    }

    @Override
    public void teleport(@Nonnull Level level, @Nonnull Vector3f position, @Nonnull Rotation rotation) {
        Level oldLevel = getLevel();
        super.teleport(level, position, rotation);

        if (oldLevel != level) {
            doDimensionChange();
        }
    }

    private void doDimensionChange() {
        // Reset spawned status
        spawned = false;
        sentChunks.clear();

        // Create the packets we will send to do the dimension change
        McpeChangeDimension changeDim0 = new McpeChangeDimension();
        changeDim0.setPosition(getGamePosition());
        changeDim0.setDimension(0);

        McpeChangeDimension changeDim1 = new McpeChangeDimension();
        changeDim1.setPosition(getGamePosition());
        changeDim1.setDimension(1);

        McpePlayStatus doRespawnPacket = new McpePlayStatus();
        doRespawnPacket.setStatus(McpePlayStatus.Status.PLAYER_SPAWN);

        // Send in order: DIM0, respawn, DIM1, respawn, empty chunks, DIM1, respawn, DIM0, respawn, actual chunks, McpeRespawn
        session.sendImmediatePackage(changeDim0);
        session.sendImmediatePackage(doRespawnPacket);
        session.sendImmediatePackage(changeDim1);
        session.sendImmediatePackage(doRespawnPacket);

        // Send a bunch of empty chunks around the new position.
        int chunkX = getPosition().getFloorX() >> 4;
        int chunkZ = getPosition().getFloorX() >> 4;

        for (int x = -3; x < 3; x++) {
            for (int z = -3; z < 3; z++) {
                McpeFullChunkData data = new McpeFullChunkData();
                data.setChunkX(chunkX + x);
                data.setChunkZ(chunkZ + z);
                data.setData(new byte[0]);
                session.sendImmediatePackage(data);
            }
        }

        // Finish sending the dimension change and respawn packets.
        session.sendImmediatePackage(changeDim1);
        session.sendImmediatePackage(doRespawnPacket);
        session.sendImmediatePackage(changeDim0);
        session.sendImmediatePackage(doRespawnPacket);

        // Now send the real chunks and then use McpeRespawn.
        sendNewChunks().whenComplete((chunks, throwable) -> {
            // Chunks sent, respawn player.
            McpeRespawn respawn = new McpeRespawn();
            respawn.setPosition(getPosition());
            session.sendImmediatePackage(respawn);
            spawned = true;

            updatePlayerList();
        });
    }

    public boolean isSpawned() {
        return spawned;
    }

    @Override
    public float getBaseSpeed() {
        return baseSpeed;
    }

    @Override
    public void setBaseSpeed(float baseSpeed) {
        Preconditions.checkArgument(Float.compare(baseSpeed, 0) > 0 && Float.compare(0.5f, baseSpeed) <= 0, "speed must be between 0 and 0.5");
        this.baseSpeed = baseSpeed;
        sendAttributes();
    }

    private class PlayerSessionNetworkPacketHandler implements NetworkPacketHandler {
        @Override
        public void handle(McpeLogin packet) {
            throw new IllegalStateException("Login packet received but player session is currently active!");
        }

        @Override
        public void handle(McpeClientMagic packet) {
            throw new IllegalStateException("Client packet received but player session is currently active!");
        }

        @Override
        public void handle(McpeRequestChunkRadius packet) {
            int radius = Math.max(5, Math.min(vwServer.getConfiguration().getMaximumViewDistance(), packet.getRadius()));
            McpeChunkRadiusUpdated updated = new McpeChunkRadiusUpdated();
            updated.setRadius(radius);
            session.sendImmediatePackage(updated);
            viewDistance = radius;

            CompletableFuture<List<Chunk>> sendChunksFuture = sendNewChunks();
            sendChunksFuture.whenComplete((chunks, throwable) -> {
                if (!spawned) {
                    McpePlayStatus status = new McpePlayStatus();
                    status.setStatus(McpePlayStatus.Status.PLAYER_SPAWN);
                    session.sendImmediatePackage(status);

                    McpeSetTime setTime = new McpeSetTime();
                    setTime.setTime(getLevel().getTime());
                    setTime.setRunning(true);
                    session.sendImmediatePackage(setTime);

                    //McpeRespawn respawn = new McpeRespawn();
                    //respawn.setPosition(getPosition());
                    //session.sendImmediatePackage(respawn);

                    updateViewableEntities();
                    sendAttributes();
                    sendPlayerInventory();
                    updatePlayerList();

                    McpeAvailableCommands availableCommands = ((VoxelwindCommandManager) vwServer.getCommandManager())
                            .generateAvailableCommandsPacket();
                    McpeBatch availableCommandsBatch = new McpeBatch();
                    availableCommandsBatch.getPackages().add(availableCommands);
                    session.sendImmediatePackage(availableCommandsBatch);

                    spawned = true;

                    PlayerJoinEvent event = new PlayerJoinEvent(PlayerSession.this, TextFormat.YELLOW + getName() + " joined the game.");
                    session.getServer().getEventManager().fire(event);
                }
            });
        }

        @Override
        public void handle(McpePlayerAction packet) {
            switch (packet.getAction()) {
                case ACTION_START_BREAK:
                    // Fire interact
                    break;
                case ACTION_ABORT_BREAK:
                    // No-op
                    break;
                case ACTION_STOP_BREAK:
                    // No-op
                    break;
                case ACTION_RELEASE_ITEM:
                    // Drop item, shoot bow, or dump bucket?
                    break;
                case ACTION_STOP_SLEEPING:
                    // Stop sleeping
                    break;
                case ACTION_RESPAWN:
                    // Clean up attributes?
                    if (!(spawned && isDead())) {
                        return;
                    }

                    setSprinting(false);
                    setSneaking(false);
                    setHealth(getMaximumHealth());
                    sendPlayerInventory();
                    teleport(getLevel(), getLevel().getSpawnLocation());
                    sendAttributes();

                    McpeRespawn respawn = new McpeRespawn();
                    respawn.setPosition(getLevel().getSpawnLocation());
                    session.addToSendQueue(respawn);
                    break;
                case ACTION_JUMP:
                    // No-op
                    break;
                case ACTION_START_SPRINT:
                    sprinting = true;
                    sendAttributes();
                    break;
                case ACTION_STOP_SPRINT:
                    sprinting = false;
                    sendAttributes();
                    break;
                case ACTION_START_SNEAK:
                    sneaking = true;
                    sendAttributes();
                    break;
                case ACTION_STOP_SNEAK:
                    sneaking = false;
                    sendAttributes();
                    break;
            }

            McpeSetEntityData dataPacket = new McpeSetEntityData();
            dataPacket.getMetadata().put(0, getFlagValue());
            getLevel().getPacketManager().queuePacketForViewers(PlayerSession.this, dataPacket);
        }

        @Override
        public void handle(McpeAnimate packet) {
            getLevel().getPacketManager().queuePacketForPlayers(packet);
        }

        @Override
        public void handle(McpeText packet) {
            if (!spawned || isDead()) {
                return;
            }

            Preconditions.checkArgument(packet.getType() == McpeText.TextType.SOURCE, "Text packet type from client is not SOURCE");
            Preconditions.checkArgument(!packet.getMessage().contains("\0"), "Text packet from client contains a null byte");
            Preconditions.checkArgument(!packet.getMessage().trim().isEmpty(), "Text packet from client is effectively empty");

            if (packet.getMessage().startsWith("/")) {
                String command = packet.getMessage().substring(1);
                try {
                    session.getServer().getCommandManager().executeCommand(PlayerSession.this, command);
                } catch (CommandNotFoundException e) {
                    sendMessage(TextFormat.RED + "No such command found.");
                } catch (CommandException e) {
                    LOGGER.error("Error while running command '{}' for {}", command, getName(), e);
                    sendMessage(TextFormat.RED + "An error has occurred while running the command.");
                }
                return;
            }

            // By default, queue this packet for all players in the world.
            getLevel().getPacketManager().queuePacketForPlayers(packet);
        }

        @Override
        public void handle(McpeMovePlayer packet) {
            if (!spawned || isDead()) {
                return;
            }

            // TODO: We may do well to perform basic anti-cheat
            Vector3f originalPosition = getPosition();
            Vector3f newPosition = packet.getPosition().sub(0, 1.62, 0);

            // Reject moves that are obviously too fast. (>=100 blocks)
            if (newPosition.distanceSquared(newPosition) >= 10000) {
                setPosition(originalPosition);
                setRotation(packet.getRotation());
                return;
            }

            setPosition(newPosition);
            setRotation(packet.getRotation());

            // If we haven't moved in the X or Z axis, don't update viewable entities or try updating chunks - they haven't changed.
            if (hasSubstantiallyMoved(originalPosition, newPosition)) {
                updateViewableEntities();
                sendNewChunks();
            }
        }

        @Override
        public void handle(McpeContainerClose packet) {
            if (!spawned || isDead()) {
                return;
            }

            if (openedInventory != null) {
                ((VoxelwindBaseInventory) openedInventory).getObserverList().remove(PlayerSession.this);
                openedInventory = null;
                openInventoryId = -1;
            }
        }

        @Override
        public void handle(McpeContainerSetSlot packet) {
            if (!spawned || isDead()) {
                return;
            }

            VoxelwindBaseInventory window = null;
            if (openInventoryId < 0 || openInventoryId != packet.getWindowId()) {
                // There's no inventory open, so it's probably the player inventory.
                if (packet.getWindowId() == 0) {
                    window = playerInventory;
                } else if (packet.getWindowId() == 0x78) {
                    // It's the armor inventory. Handle it here.
                    switch (packet.getSlot()) {
                        case 0:
                            getEquipment().setHelmet(packet.getStack());
                            break;
                        case 1:
                            getEquipment().setChestplate(packet.getStack());
                            break;
                        case 2:
                            getEquipment().setLeggings(packet.getStack());
                            break;
                        case 3:
                            getEquipment().setBoots(packet.getStack());
                            break;
                    }
                    return;
                }
            } else {
                window = (VoxelwindBaseInventory) openedInventory;
            }

            if (window == null) {
                return;
            }

            window.setItem(packet.getSlot(), packet.getStack(), PlayerSession.this);
        }

        @Override
        public void handle(McpeMobEquipment packet) {
            if (!spawned || isDead()) {
                return;
            }

            // Basic sanity check:
            if (packet.getHotbarSlot() < 0 || packet.getHotbarSlot() >= 9) {
                throw new IllegalArgumentException("Specified hotbar slot " + packet.getHotbarSlot() + " isn't valid.");
            }

            int correctedInventorySlot = packet.getInventorySlot() - 9;
            int finalSlot = correctedInventorySlot < 0 || correctedInventorySlot >= playerInventory.getInventoryType().getInventorySize() ?
                    -1 : correctedInventorySlot;

            playerInventory.setHotbarLink(packet.getHotbarSlot(), finalSlot);
            playerInventory.setHeldHotbarSlot(packet.getHotbarSlot(), true);
        }

        @Override
        public void handle(McpeRemoveBlock packet) {
            if (!spawned || isDead()) {
                return;
            }

            // TODO: Perform sanity checks and drop items.
            int chunkX = packet.getPosition().getX() >> 4;
            int chunkZ = packet.getPosition().getZ() >> 4;

            Optional<Chunk> chunkOptional = getLevel().getChunkIfLoaded(chunkX, chunkZ);
            if (!chunkOptional.isPresent()) {
                // Chunk not loaded, danger ahead!
                LOGGER.error("{} tried to remove block at unloaded chunk ({}, {})", getName(), chunkX, chunkZ);
                return;
            }

            int inChunkX = packet.getPosition().getX() & 0x0f;
            int inChunkZ = packet.getPosition().getZ() & 0x0f;

            Block block = chunkOptional.get().getBlock(inChunkX, packet.getPosition().getY(), inChunkZ);
            // Call BlockReplaceEvent.
            BlockReplaceEvent event = new BlockReplaceEvent(block, block.getBlockState(), new BasicBlockState(BlockTypes.AIR, null, null),
                    PlayerSession.this, BlockReplaceEvent.ReplaceReason.PLAYER_BREAK);
            getServer().getEventManager().fire(event);
            if (event.getResult() == BlockReplaceEvent.Result.CONTINUE) {
                if (gameMode != GameMode.CREATIVE) {
                    BlockBehavior blockBehavior = BlockBehaviors.getBlockBehavior(block.getBlockState().getBlockType());
                    if (!blockBehavior.handleBreak(getServer(), PlayerSession.this, block, playerInventory.getStackInHand().orElse(null))) {
                        Collection<ItemStack> drops = blockBehavior.getDrops(getServer(), PlayerSession.this, block, playerInventory.getStackInHand().orElse(null));
                        for (ItemStack drop : drops) {
                            DroppedItem item = getLevel().dropItem(drop, block.getLevelLocation().toFloat().add(0.5, 0.5, 0.5));
                            item.setDelayPickupTicks(5);
                        }
                        chunkOptional.get().setBlock(inChunkX, packet.getPosition().getY(), inChunkZ, new BasicBlockState(BlockTypes.AIR, null, null));
                    }
                } else {
                    chunkOptional.get().setBlock(inChunkX, packet.getPosition().getY(), inChunkZ, new BasicBlockState(BlockTypes.AIR, null, null));
                }
            }

            getLevel().broadcastBlockUpdate(packet.getPosition());
        }

        @Override
        public void handle(McpeUseItem packet) {
            if (!spawned || isDead()) {
                return;
            }

            if (packet.getFace() == 0xff) {
                // TODO: Snowballs.
            } else if (packet.getFace() >= 0 && packet.getFace() <= 5) {
                // Sanity check:
                Optional<ItemStack> actuallyInHand = playerInventory.getStackInHand();
                if (LOGGER.isDebugEnabled()) {
                    LOGGER.debug("Held: {}, slot: {}", actuallyInHand, playerInventory.getHeldHotbarSlot());
                }
                if ((actuallyInHand.isPresent() && actuallyInHand.get().getItemType() != packet.getStack().getItemType()) ||
                        !actuallyInHand.isPresent() && packet.getStack().getItemType() == BlockTypes.AIR) {
                    // Not actually the same item.
                    return;
                }

                // What block is this item being used against?
                Optional<Block> usedAgainst = getLevel().getBlockIfChunkLoaded(packet.getLocation());
                if (!usedAgainst.isPresent()) {
                    // Not loaded into memory.
                    return;
                }

                // Ask the block being checked.
                ItemStack serverInHand = actuallyInHand.orElse(null);
                BlockFace face = BlockFace.values()[packet.getFace()];
                BlockBehavior againstBehavior = BlockBehaviors.getBlockBehavior(usedAgainst.get().getBlockState().getBlockType());
                switch (againstBehavior.handleItemInteraction(getServer(), PlayerSession.this, packet.getLocation(), face, serverInHand)) {
                    case NOTHING:
                        // Update inventory
                        sendPlayerInventory();
                        break;
                    case PLACE_BLOCK_AND_REMOVE_ITEM:
                        Preconditions.checkState(serverInHand != null && serverInHand.getItemType() instanceof BlockType, "Tried to place air or non-block.");
                        if (!BehaviorUtils.setBlockState(PlayerSession.this, packet.getLocation().add(face.getOffset()), BehaviorUtils.createBlockState(usedAgainst.get().getLevelLocation(), face, serverInHand))) {
                            sendPlayerInventory();
                            return;
                        }
                        // This will fall through
                    case REMOVE_ONE_ITEM:
                        if (serverInHand != null) {
                            int newItemAmount = serverInHand.getAmount() - 1;
                            if (newItemAmount <= 0) {
                                playerInventory.clearItem(playerInventory.getHeldInventorySlot());
                            } else {
                                playerInventory.setItem(playerInventory.getHeldInventorySlot(), serverInHand.toBuilder().amount(newItemAmount).build());
                            }
                        }
                        break;
                    case REDUCE_DURABILITY:
                        // TODO: Implement
                        break;
                }
            }
        }

        @Override
        public void handle(McpeDropItem packet) {
            if (!spawned || isDead()) {
                return;
            }

            if (packet.getItem().getItemType() == BlockTypes.AIR) {
                return;
            }

            // TODO: Events
            Optional<ItemStack> stackOptional = playerInventory.getStackInHand();
            if (!stackOptional.isPresent()) {
                sendPlayerInventory();
                return;
            }

            DroppedItem item = new VoxelwindDroppedItem(getLevel(), getPosition().add(0, 1.3, 0), getServer(), stackOptional.get());
            item.setMotion(getDirectionVector().mul(0.4));
            playerInventory.clearItem(playerInventory.getHeldInventorySlot());
        }

        @Override
        public void handle(McpeResourcePackClientResponse packet) {
            // TODO: Stack packet?
            doInitialSpawn();
        }

        @Override
        public void handle(McpeCommandStep packet) {
            if (!spawned || isDead()) {
                return;
            }

            // This is essentially a hack at the moment.
            // TODO: Replace with nicer command API
            JsonNode argsNode;
            try {
                argsNode = VoxelwindServer.MAPPER.readTree(packet.getArgs());
            } catch (IOException e) {
                LOGGER.error("Unable to decode command argument JSON", e);
                return;
            }

            String command = null;
            if (argsNode.getNodeType() == JsonNodeType.NULL) {
                command = packet.getCommand();
            } else if (argsNode.getNodeType() == JsonNodeType.OBJECT) {
                JsonNode innerArgs = argsNode.get("args");
                if (innerArgs.getNodeType() == JsonNodeType.STRING) {
                    command = packet.getCommand() + " " + innerArgs.asText();
                } else if (innerArgs.getNodeType() == JsonNodeType.ARRAY) {
                    StringBuilder reconstructedCommand = new StringBuilder(packet.getCommand());
                    ArrayNode innerArgsArray = (ArrayNode) innerArgs;
                    for (JsonNode node : innerArgsArray) {
                        reconstructedCommand.append(' ').append(node.asText());
                    }
                    command = reconstructedCommand.toString();
                }
            }

            if (command == null) {
                LOGGER.debug("Unable to reconstruct command for packet {}", packet);
                sendMessage(TextFormat.RED + "An error has occurred while running the command.");
                return;
            }

            try {
                session.getServer().getCommandManager().executeCommand(PlayerSession.this, command);
            } catch (CommandNotFoundException e) {
                sendMessage(TextFormat.RED + "No such command found.");
            } catch (CommandException e) {
                LOGGER.error("Error while running command '{}' for {}", command, getName(), e);
                sendMessage(TextFormat.RED + "An error has occurred while running the command.");
            }
        }
    }

    private void updatePlayerList() {
        synchronized (playersSentForList) {
            Set<Player> toAdd = new HashSet<>();
            Set<UUID> toRemove = new HashSet<>();
            Map<UUID, PlayerSession> availableSessions = new HashMap<>();
            for (PlayerSession session : getLevel().getEntityManager().getPlayers()) {
                availableSessions.put(session.getUniqueId(), session);
            }

            for (Player player : availableSessions.values()) {
                if (playersSentForList.add(player.getUniqueId())) {
                    toAdd.add(player);
                }
            }

            for (UUID uuid : playersSentForList) {
                if (!availableSessions.containsKey(uuid)) {
                    toRemove.add(uuid);
                }
            }

            if (!toAdd.isEmpty()) {
                McpePlayerList list = new McpePlayerList();
                list.setType((byte) 0);
                for (Player player : toAdd) {
                    McpePlayerList.Record record = new McpePlayerList.Record(player.getUniqueId());
                    record.setEntityId(player.getEntityId());
                    record.setSkin(player.getSkin());
                    record.setName(player.getName());
                    list.getRecords().add(record);
                }
                session.addToSendQueue(list);
            }

            if (!toRemove.isEmpty()) {
                playersSentForList.removeAll(toRemove);

                McpePlayerList list = new McpePlayerList();
                list.setType((byte) 1);
                for (UUID uuid : toRemove) {
                    list.getRecords().add(new McpePlayerList.Record(uuid));
                }
                session.addToSendQueue(list);
            }
        }
    }

    private static class AroundPointComparator implements Comparator<Chunk> {
        private final int spawnX;
        private final int spawnZ;

        private AroundPointComparator(int spawnX, int spawnZ) {
            this.spawnX = spawnX;
            this.spawnZ = spawnZ;
        }

        @Override
        public int compare(Chunk o1, Chunk o2) {
            // Use whichever is closest to the origin.
            return Integer.compare(distance(o1.getX(), o1.getZ()), distance(o2.getX(), o2.getZ()));
        }

        private int distance(int x, int z) {
            int dx = spawnX - x;
            int dz = spawnZ - z;
            return dx * dx + dz * dz;
        }
    }

    private static final System PLAYER_SYSTEM = System.builder()
            .expectComponent(Health.class)
            .runner(new PlayerTickSystemRunner())
            .build();

    private static class PlayerTickSystemRunner implements SystemRunner {
        @Override
        public void run(Entity entity) {
            Verify.verify(entity instanceof PlayerSession, "Invalid entity type (need PlayerSession)");
            PlayerSession session = (PlayerSession) entity;

            Optional<Health> healthOptional = session.getComponent(Health.class);
            if (!healthOptional.isPresent()) {
                throw new VerifyException("Health component is missing.");
            }
            Health health = healthOptional.get();

            if (!session.isSpawned() || health.isDead()) {
                // Don't tick until the player has truly been spawned into the world.
                return;
            }

            // If the upstream session is closed, the player session should no longer be alive.
            if (session.getMcpeSession().isClosed()) {
                session.removeInternal();
                return;
            }

            if (session.hasMoved) {
                session.hasMoved = false;
                if (session.isTeleported()) {
                    session.sendMovePlayerPacket();
                }
                session.updateViewableEntities();
                session.sendNewChunks();
            }

            // Check for items on the ground.
            // TODO: This should be its own system
            session.pickupAdjacent();

            // Update player list.
            // TODO: Packet doesn't currently encode correctly.
            //updatePlayerList();
        }
    }

    private static boolean hasSubstantiallyMoved(Vector3f oldPos, Vector3f newPos) {
        return (Float.compare(oldPos.getX(), newPos.getX()) != 0 || Float.compare(oldPos.getZ(), newPos.getZ()) != 0);
    }
}
