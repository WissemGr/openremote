/*
 * Copyright 2020, OpenRemote Inc.
 *
 * See the CONTRIBUTORS.txt file in the distribution for a
 * full listing of individual contributors.
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as
 * published by the Free Software Foundation, either version 3 of the
 * License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <http://www.gnu.org/licenses/>.
 */
package org.openremote.manager.gateway;

import org.openremote.container.timer.TimerService;
import org.openremote.manager.asset.AssetProcessingService;
import org.openremote.manager.asset.AssetStorageService;
import org.openremote.model.asset.*;
import org.openremote.model.asset.agent.ConnectionStatus;
import org.openremote.model.asset.impl.GatewayAsset;
import org.openremote.model.attribute.AttributeEvent;
import org.openremote.model.event.shared.SharedEvent;
import org.openremote.model.gateway.*;
import org.openremote.model.query.AssetQuery;
import org.openremote.model.syslog.SyslogCategory;
import org.openremote.model.util.Pair;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BiFunction;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToIntFunction;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import static org.openremote.model.syslog.SyslogCategory.GATEWAY;

/**
 * Handles all communication between a gateway and this manager instance
 */
public class GatewayConnector {

    private static final Logger LOG = SyslogCategory.getLogger(GATEWAY, GatewayConnector.class.getName());
    public static int MAX_SYNC_RETRIES = 5;
    public static int SYNC_ASSET_BATCH_SIZE = 20;
    public static final String ASSET_READ_EVENT_NAME_INITIAL = "INITIAL";
    public static final String ASSET_READ_EVENT_NAME_BATCH = "BATCH";
    public static final long RESPONSE_TIMEOUT_MILLIS = 10000;
    protected static final Map<String, Pair<Function<String, String>, Function<String, String>>> ASSET_ID_MAPPERS = new ConcurrentHashMap<>();
    protected final String realm;
    protected final String gatewayId;
    protected final AssetStorageService assetStorageService;
    protected final ExecutorService executorService;
    protected final ScheduledExecutorService scheduledExecutorService;
    protected final AssetProcessingService assetProcessingService;
    protected final GatewayService gatewayService;
    protected final TimerService timerService;
    protected List<AssetEvent> cachedAssetEvents;
    protected List<AttributeEvent> cachedAttributeEvents;
    protected Consumer<Object> gatewayMessageConsumer;
    protected Runnable requestDisconnect;
    protected final AtomicReference<String> sessionId = new AtomicReference<>();
    protected boolean disabled;
    protected boolean initialSyncInProgress;
    protected ScheduledFuture<?> syncProcessorFuture;
    protected Future<?> capabilitiesFuture;
    List<String> syncAssetIds;
    protected GatewayAsset gatewayAsset;
    int syncIndex;
    int syncErrors;
    String expectedSyncResponseName;
    protected boolean tunnellingSupported;
    protected final Map<Class<? extends SharedEvent>, Consumer<SharedEvent>> eventConsumerMap = new HashMap<>();

    protected static List<Integer> ALPHA_NUMERIC_CHARACTERS = new ArrayList<>(62);

    static {
        ALPHA_NUMERIC_CHARACTERS.addAll(
            Stream.concat(
                Stream.concat(
                    IntStream.rangeClosed('a', 'z').boxed(),
                    IntStream.rangeClosed('A', 'Z').boxed()
                ),
                IntStream.rangeClosed('0', '9').boxed()
            ).toList()
        );
    }

    protected GatewayConnector(
        AssetStorageService assetStorageService,
        AssetProcessingService assetProcessingService,
        ExecutorService executorService,
        ScheduledExecutorService scheduledExecutorService,
        GatewayService gatewayService,
        TimerService timerService,
        GatewayAsset gateway) {

        this.assetStorageService = assetStorageService;
        this.assetProcessingService = assetProcessingService;
        this.executorService = executorService;
        this.scheduledExecutorService = scheduledExecutorService;
        this.gatewayService = gatewayService;
        this.timerService = timerService;
        this.disabled = gateway.getDisabled().orElse(false);
        this.realm = gateway.getRealm();
        this.gatewayId = gateway.getId();
        this.gatewayAsset = gateway;

        // Setup static inbound event handling
        synchronized(eventConsumerMap) {
            eventConsumerMap.put(AssetEvent.class, (e) -> onAssetEvent((AssetEvent) e));
            eventConsumerMap.put(AttributeEvent.class, (e) -> onAttributeEvent((AttributeEvent) e));
        }
        LOG.finest("Setting connection status=" + ConnectionStatus.DISCONNECTED + ": " + getGatewayIdString());
        publishAttributeEvent(new AttributeEvent(gatewayId, GatewayAsset.STATUS, ConnectionStatus.DISCONNECTED, timerService.getNow()));
    }

    protected void sendMessageToGateway(Object message) {
        try {
            if (gatewayMessageConsumer != null) {
                gatewayMessageConsumer.accept(message);
                if (LOG.isLoggable(Level.FINEST)) {
                    LOG.fine(() -> "Sending message '" + message + "': " + getGatewayIdString());
                } else {
                    LOG.fine(() -> "Sending message: " + getGatewayIdString());
                }
            }
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to send message: " + getGatewayIdString(), e.getMessage());
        }
    }

    /**
     * Connection for this gateway has started so initiate synchronisation of assets
     */
    protected void connected(String sessionId, Consumer<Object> gatewayMessageConsumer, Runnable requestDisconnect) {

        LOG.info("Connected: " + getGatewayIdString());

        synchronized (this.sessionId) {
            if (getSessionId() != null) {
                disconnect(GatewayDisconnectEvent.Reason.ALREADY_CONNECTED);
            }
            this.sessionId.set(sessionId);
        }
        this.gatewayMessageConsumer = gatewayMessageConsumer;
        this.requestDisconnect = requestDisconnect;

        // Reinitialise state
        initialSyncInProgress = true;
        syncProcessorFuture = null;
        cachedAssetEvents = new ArrayList<>();
        cachedAttributeEvents = new ArrayList<>();
        syncAssetIds = null;
        syncIndex = 0;
        syncErrors = 0;

        LOG.finest("Setting connection status=" + ConnectionStatus.CONNECTING + ": " + getGatewayIdString());
        publishAttributeEvent(new AttributeEvent(gatewayId, GatewayAsset.STATUS, ConnectionStatus.CONNECTING, timerService.getNow()));
        startSync();
    }

    /**
     * Connection to the edge gateway instance has been disconnected so stop any synchronisation
     */
    protected void disconnected(String sessionId) {
        synchronized (this.sessionId) {
            if (!sessionId.equals(this.sessionId.get())) {
                return;
            }
            this.sessionId.set(null);
        }

        requestDisconnect.run();
        LOG.fine("Disconnected: " + getGatewayIdString());
        if (syncProcessorFuture != null) {
            LOG.finest("Aborting active sync process: " + getGatewayIdString());
            syncProcessorFuture.cancel(true);
        }
        if (capabilitiesFuture != null) {
            LOG.finest("Aborting capabilities request: " + getGatewayIdString());
            capabilitiesFuture.cancel(true);
        }

        initialSyncInProgress = false;
        LOG.finest("Setting connection status=" + ConnectionStatus.DISCONNECTED + ": " + getGatewayIdString());
        publishAttributeEvent(new AttributeEvent(gatewayId, GatewayAsset.STATUS, ConnectionStatus.DISCONNECTED, timerService.getNow()));
    }

    protected void disconnect(GatewayDisconnectEvent.Reason reason) {
        synchronized (this.sessionId) {
            if (isConnected()) {
                if (!disabled) {
                    sendMessageToGateway(new GatewayDisconnectEvent(reason));
                }
                disconnected(getSessionId());
            }
        }
    }

    protected boolean isConnected() {
        return sessionId.get() != null;
    }

    protected boolean isInitialSyncInProgress() {
        return initialSyncInProgress;
    }

    protected boolean isTunnellingSupported() {
        return tunnellingSupported;
    }

    /**
     * Request for gateway capabilities such as tunneling support
     */
    protected void getCapabilities() {

        CompletableFuture<GatewayCapabilitiesResponseEvent> future = new CompletableFuture<>();

        synchronized (eventConsumerMap) {
            if (eventConsumerMap.containsKey(GatewayCapabilitiesResponseEvent.class)) {
                return;
            }

            eventConsumerMap.put(GatewayCapabilitiesResponseEvent.class, (e) -> {
                GatewayCapabilitiesResponseEvent response = (GatewayCapabilitiesResponseEvent) e;
                future.complete(response);
            });
        }

        LOG.finest("Requesting gateway capabilities: " + getGatewayIdString());
        sendMessageToGateway(new GatewayCapabilitiesRequestEvent());

        future
            .orTimeout(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .whenComplete ((response, error) -> {
                synchronized (eventConsumerMap) {
                    eventConsumerMap.remove(GatewayCapabilitiesResponseEvent.class);  // Cleanup
                }
                if (error != null) {
                    if (error instanceof TimeoutException) {
                        LOG.warning("Capabilities request timeout error, assuming no support: " + getGatewayIdString());
                    } else {
                        LOG.warning("Capabilities request error [" + error.getMessage() + "] assuming no support: " + getGatewayIdString());
                    }
                }
                tunnellingSupported = response != null && response.isTunnelingSupported();
                LOG.finest("Tunnelling supported=" + tunnellingSupported + ": " + getGatewayIdString());
                publishAttributeEvent(new AttributeEvent(gatewayId, GatewayAsset.TUNNELING_SUPPORTED, tunnellingSupported));
                LOG.finest("Setting connection status=" + ConnectionStatus.CONNECTED + ": " + getGatewayIdString());
                publishAttributeEvent(new AttributeEvent(gatewayId, GatewayAsset.STATUS, ConnectionStatus.CONNECTED, timerService.getNow()));
            });
    }

    protected CompletableFuture<Void> startTunnel(GatewayTunnelInfo tunnelInfo) {

        if (!isConnected() || isInitialSyncInProgress()) {
            String msg = "Start tunnel failed not connected or initial sync in progress: " + getGatewayIdString();
            LOG.info(msg);
            throw new IllegalStateException(msg);
        }

        CompletableFuture<Void> future = new CompletableFuture<>();

        synchronized (eventConsumerMap) {
            if (eventConsumerMap.containsKey(GatewayTunnelStartResponseEvent.class)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("A start tunnel request is already pending"));
            }

            eventConsumerMap.put(GatewayTunnelStartResponseEvent.class, (e) -> {
                GatewayTunnelStartResponseEvent response = (GatewayTunnelStartResponseEvent) e;
                if (response != null && response.getError() != null) {
                    throw new RuntimeException("Failed to start tunnel: error=" + response.getError() + ", " + tunnelInfo);
                }
                future.complete(null);
            });
        }

        sendMessageToGateway(
                new GatewayTunnelStartRequestEvent(gatewayService.getTunnelSSHHostname(), gatewayService.getTunnelSSHPort(), tunnelInfo)
        );

        return future
                .orTimeout(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
                .whenComplete((result, ex) -> {
                    synchronized (eventConsumerMap) {
                        eventConsumerMap.remove(GatewayTunnelStartResponseEvent.class);
                    }
                    if (ex != null && !(ex instanceof TimeoutException)) {
                        // Re-throw unexpected exceptions
                        throw new RuntimeException("Failed to get gateway response", ex);
                    }
                });
    }

    protected CompletableFuture<Void> stopTunnel(GatewayTunnelInfo tunnelInfo) {

        CompletableFuture<Void> future = new CompletableFuture<>();

        synchronized (eventConsumerMap) {
            if (eventConsumerMap.containsKey(GatewayTunnelStopResponseEvent.class)) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("A stop tunnel request is already pending"));
            }

            eventConsumerMap.put(GatewayTunnelStopResponseEvent.class, (e) -> {
                GatewayTunnelStopResponseEvent response = (GatewayTunnelStopResponseEvent) e;
                if (response != null && response.getError() != null) {
                    throw new RuntimeException("Failed to stop tunnel: error=" + response.getError() + ", " + tunnelInfo);
                }
                future.complete(null);
            });
        }

        sendMessageToGateway(new GatewayTunnelStopRequestEvent(tunnelInfo));

        return future
            .orTimeout(RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)
            .whenComplete((result, ex) -> {
                synchronized (eventConsumerMap) {
                    eventConsumerMap.remove(GatewayTunnelStopResponseEvent.class);
                }
                if (ex != null && !(ex instanceof TimeoutException)) {
                    // Re-throw unexpected exceptions
                    throw new RuntimeException("Failed to get gateway response", ex);
                }
            });
    }

    protected String getRealm() {
        return realm;
    }

    protected boolean isDisabled() {
        return disabled;
    }

    protected void setDisabled(boolean disabled) {
        this.disabled = disabled;
        disconnect(GatewayDisconnectEvent.Reason.DISABLED);
    }

    protected String getSessionId() {
        return sessionId.get();
    }

    protected void publishAttributeEvent(AttributeEvent event) {
        assetProcessingService.sendAttributeEvent(event, GatewayService.class.getSimpleName());
    }

    synchronized protected void onGatewayEvent(SharedEvent e) {
        try {
            if (initialSyncInProgress) {
                if (e instanceof AssetsEvent) {
                    onSyncAssetsResponse((AssetsEvent) e);
                } else if (e instanceof AttributeEvent) {
                    cachedAttributeEvents.add((AttributeEvent) e);
                } else if (e instanceof AssetEvent) {
                    cachedAssetEvents.add((AssetEvent) e);
                }
            } else {
                synchronized (eventConsumerMap) {
                    Consumer<SharedEvent> consumer = eventConsumerMap.get(e.getClass());
                    if (consumer != null) {
                        consumer.accept(e);
                    }
                }
            }
        } catch (Exception ex) {
            LOG.log(Level.WARNING, ex, () -> "An error occurred while processing a gateway event: event=" + e + ": " + getGatewayIdString());
            disconnect(GatewayDisconnectEvent.Reason.SYNC_ERROR);
        }
    }

    /**
     * Get list of gateway assets (get basic details and then batch load them to minimise load)
     */
    synchronized protected void startSync() {

        if (syncAborted()) {
            return;
        }

        expectedSyncResponseName = ASSET_READ_EVENT_NAME_INITIAL;
        ReadAssetsEvent event = new ReadAssetsEvent(new AssetQuery().select(new AssetQuery.Select().excludeAttributes()).recursive(true));
        event.setMessageID(expectedSyncResponseName);
        sendMessageToGateway(event);
        syncProcessorFuture = scheduledExecutorService.schedule(this::onSyncAssetsTimeout, RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    /**
     * Called if a response isn't received from the gateway within {@link #RESPONSE_TIMEOUT_MILLIS}
     */
    synchronized protected void onSyncAssetsTimeout() {
        if (!isConnected()) {
            return;
        }

        LOG.info("Asset sync timeout occurred: " + getGatewayIdString());
        syncErrors++;

        if (syncAborted()) {
            return;
        }

        if (syncAssetIds == null) {
            // Haven't received initial list of assets so retry
            startSync();
        } else {
            requestAssets();
        }
    }

    protected boolean syncAborted() {
        if (syncErrors == MAX_SYNC_RETRIES) {
            LOG.warning("Asset sync max retries reached so disconnecting: " + getGatewayIdString());
            disconnect(GatewayDisconnectEvent.Reason.SYNC_ERROR);
            return true;
        }

        return false;
    }

    /**
     * Request assets in batches of {@link #SYNC_ASSET_BATCH_SIZE} to avoid overloading the gateway
     */
    protected void requestAssets() {

        if (syncAborted()) {
            return;
        }

        String[] requestAssetIds = syncAssetIds.stream().skip(syncIndex).limit(SYNC_ASSET_BATCH_SIZE).toArray(String[]::new);
        expectedSyncResponseName = ASSET_READ_EVENT_NAME_BATCH + syncIndex;

        LOG.fine("Synchronising assets " + syncIndex+1 + "-" + syncIndex + requestAssetIds.length + " of " + syncAssetIds.size() + ": " + getGatewayIdString());
        ReadAssetsEvent event = new ReadAssetsEvent(
            new AssetQuery()
                .ids(requestAssetIds)
        );
        event.setMessageID(expectedSyncResponseName);
        sendMessageToGateway(event);
        syncProcessorFuture = scheduledExecutorService.schedule(this::requestAssets, RESPONSE_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS);
    }

    synchronized protected void onSyncAssetsResponse(AssetsEvent e) {
        if (!isConnected()) {
            return;
        }

        String messageId = e.getMessageID();

        if (!expectedSyncResponseName.equalsIgnoreCase(messageId)) {
            LOG.warning("Unexpected asset sync response so ignoring (expected=" + expectedSyncResponseName + ", actual =" + messageId + "): " + getGatewayIdString());
            return;
        }

        syncProcessorFuture.cancel(true);
        syncProcessorFuture = null;
        boolean isInitialResponse = ASSET_READ_EVENT_NAME_INITIAL.equalsIgnoreCase(messageId);

        if (isInitialResponse) {

            // Put assets in hierarchical order
            Map<String, String> gatewayAssetIdParentIdMap = e.getAssets() == null ? Collections.emptyMap() : e.getAssets().stream()
                .collect(HashMap::new, (m, v) -> m.put(v.getId(), v.getParentId()), HashMap::putAll);

            ToIntFunction<Asset<?>> assetLevelExtractor = asset -> {
                int level = 0;
                String parentId = asset.getParentId();
                while (parentId != null) {
                    level++;
                    parentId = gatewayAssetIdParentIdMap.get(parentId);
                }
                return level;
            };

            syncAssetIds =  e.getAssets() == null ? Collections.emptyList() : e.getAssets()
                .stream()
                .sorted(Comparator.comparingInt(assetLevelExtractor))
                .map(Asset::getId)
                .collect(Collectors.toList());

            if (syncAssetIds.isEmpty()) {
                deleteObsoleteLocalAssets();
                onInitialSyncComplete();
                return;
            }

            requestAssets();

        } else {

            if (syncAssetIds == null) {
                LOG.warning("Unexpected asset sync message, so requesting disconnect: " + getGatewayIdString());
                disconnect(GatewayDisconnectEvent.Reason.SYNC_ERROR);
                return;
            }

            List<String> requestedAssetIds = syncAssetIds.stream().skip(syncIndex).limit(SYNC_ASSET_BATCH_SIZE).collect(Collectors.toList());
            List<Asset<?>> returnedAssets = e.getAssets() == null ? Collections.emptyList() : e.getAssets();

            // Remove any assets that have been deleted since requested
            cachedAssetEvents.removeIf(
                assetEvent -> {
                    boolean remove = requestedAssetIds.stream().anyMatch(id -> id.equals(assetEvent.getId()) && assetEvent.getCause() == AssetEvent.Cause.DELETE);
                    if (remove) {
                        syncAssetIds.remove(assetEvent.getId());
                        requestedAssetIds.remove(assetEvent.getId());
                    }
                    return remove;
                });

            if (returnedAssets.size() != requestedAssetIds.size() || !returnedAssets.stream().allMatch(asset -> requestedAssetIds.contains(asset.getId()))) {
                LOG.warning("Unexpected asset sync response invalid batch count or ID mismatch, attempting to re-send the request: " + getGatewayIdString());
                syncErrors++;
                requestAssets();
                return;
            }

            // Returned asset order may not match request order so re-order
            returnedAssets = returnedAssets.stream()
                .sorted(Comparator.comparingInt(a -> syncAssetIds.indexOf(a.getId())))
                .toList();

            // Merge returned assets ensuring the latest version of each is merged
            returnedAssets.stream()
                .map(returnedAsset -> {
                    final AtomicReference<Asset<?>> latestAssetVersion = new AtomicReference<>(returnedAsset);
                    cachedAssetEvents.removeIf(
                        assetEvent -> {
                            boolean remove = assetEvent.getId().equals(returnedAsset.getId()) && (assetEvent.getCause() == AssetEvent.Cause.UPDATE || assetEvent.getCause() == AssetEvent.Cause.READ);
                            if (remove && assetEvent.getAsset().getVersion() > latestAssetVersion.get().getVersion()) {
                                latestAssetVersion.set(assetEvent.getAsset());
                            }
                            return remove;
                        });
                    return latestAssetVersion.get();
                }).forEach(this::saveAssetLocally);


            // Request next batch or move on
            syncIndex += requestedAssetIds.size();
            if (syncIndex >= syncAssetIds.size()) {
                LOG.finest("Asset sync completed: " + getGatewayIdString());

                Set<String> refreshAssets = new HashSet<>();

                cachedAssetEvents.forEach(
                    assetEvent -> {
                        if (assetEvent.getCause() == AssetEvent.Cause.DELETE) {
                            syncAssetIds.remove(assetEvent.getId());
                        } else if (assetEvent.getCause() == AssetEvent.Cause.CREATE) {
                            syncAssetIds.add(assetEvent.getId());
                            try {
                                saveAssetLocally(assetEvent.getAsset());
                            } catch (Exception ex) {
                                LOG.log(Level.WARNING, "Failed to add new gateway asset (Asset=" + assetEvent.getAsset() + "): " + getGatewayIdString(), ex);
                            }
                        } else {
                            refreshAssets.add(assetEvent.getId());
                        }
                    }
                );

                deleteObsoleteLocalAssets();
                onInitialSyncComplete();

                // Refresh attributes that have changed
                cachedAttributeEvents.forEach(attributeEvent -> {
                    String assetId = attributeEvent.getId();
                    if (!refreshAssets.contains(assetId)) {
                        LOG.info("1 or more asset attribute values have changed so requesting the asset again (Asset<?> ID=" + assetId + ": " + getGatewayIdString());
                        refreshAssets.add(assetId);
                    }
                });

                // Refresh assets that have changed
                refreshAssets.forEach(id -> sendMessageToGateway(new ReadAssetEvent(id)));
            } else {
                requestAssets();
            }
        }
    }

    protected void deleteObsoleteLocalAssets() {

        // Find obsolete local assets
        List<Asset<?>> localAssets = assetStorageService.findAll(
            new AssetQuery()
                .select(new AssetQuery.Select().excludeAttributes())
                .recursive(true)
                .parents(gatewayId)
        );

        // Delete obsolete assets
        List<String> obsoleteLocalAssetIds = localAssets.stream()
            .map(Asset::getId)
            .filter(id -> !syncAssetIds.contains(mapAssetId(gatewayId, id, true)))
            .toList();

        if (!obsoleteLocalAssetIds.isEmpty()) {
            boolean deleted = deleteAssetsLocally(obsoleteLocalAssetIds);
            if (!deleted) {
                LOG.warning("Failed to delete obsolete local assets; assets are not correctly synced: " + getGatewayIdString());
            }
        }
    }

    protected void onInitialSyncComplete() {
        LOG.info("Initial sync complete: " + gatewayId);
        initialSyncInProgress = false;
        cachedAssetEvents.clear();
        cachedAttributeEvents.clear();
        getCapabilities();
    }

    @SuppressWarnings("SynchronizationOnLocalVariableOrMethodParameter")
    synchronized protected void onAssetEvent(AssetEvent e) {

        switch (e.getCause()) {
            case CREATE, READ, UPDATE -> saveAssetLocally(e.getAsset());
            case DELETE -> deleteAssetsLocally(Collections.singletonList(mapAssetId(gatewayId, e.getId(), false)));
        }
    }

    protected void onAttributeEvent(AttributeEvent e) {
        // Just push the event through the processing chain
        publishAttributeEvent(new AttributeEvent(mapAssetId(gatewayId, e.getId(), false), e.getName(), e.getValue().orElse(null), e.getTimestamp()));
    }

    protected <T extends Asset<?>> T saveAssetLocally(T asset) {
        String assetId = asset.getId();
        asset.setId(mapAssetId(gatewayId, assetId, false));
        asset.setParentId(asset.getParentId() != null ? mapAssetId(gatewayId, asset.getParentId(), false) : gatewayId);
        asset.setRealm(realm);
        LOG.finest("Creating/updating asset: Asset ID=" + assetId + ", Asset ID Mapped=" + asset.getId() + ": " + getGatewayIdString());
        return assetStorageService.merge(asset, true, gatewayAsset, null);
    }

    protected boolean deleteAssetsLocally(List<String> assetIds) {
        LOG.finest("Removing gateway asset: Asset IDs=" + Arrays.toString(assetIds.toArray()) + ": " + getGatewayIdString());
        return assetStorageService.delete(assetIds, true);
    }

    public GatewayAsset getGatewayAsset() {
        return gatewayAsset;
    }

    @Override
    public String toString() {
        return GatewayConnector.class.getSimpleName() + "{" +
            "gatewayId='" + gatewayId + '\'' +
            '}';
    }

    protected String getGatewayIdString() {
        return "GatewayID=" + gatewayId;
    }

    /**
     * An easily reversible mathematical way of ensuring gateway asset IDs are unique by incrementing the first two
     * characters by adding the first two characters of the gateway ID for inbound IDs and the reverse for outbound.
     */
    public static String mapAssetId(String gatewayId, String assetId, boolean outbound) {
        Pair<Function<String, String>, Function<String, String>> gatewayIdMappers = ASSET_ID_MAPPERS.computeIfAbsent(gatewayId, gwId -> {
            int g1 = gatewayId.charAt(0) % ALPHA_NUMERIC_CHARACTERS.size();
            int g2 = gatewayId.charAt(1) % ALPHA_NUMERIC_CHARACTERS.size();

            BiFunction<Integer, String, String> mapper = (sign, id) -> {
                int a1 = (ALPHA_NUMERIC_CHARACTERS.indexOf((int)id.charAt(0)) + (sign * g1) + ALPHA_NUMERIC_CHARACTERS.size()) % ALPHA_NUMERIC_CHARACTERS.size();
                int a2 = (ALPHA_NUMERIC_CHARACTERS.indexOf((int)id.charAt(1)) + (sign * g2) + ALPHA_NUMERIC_CHARACTERS.size()) % ALPHA_NUMERIC_CHARACTERS.size();
                return String.valueOf((char)ALPHA_NUMERIC_CHARACTERS.get(a1).intValue()) + ((char)ALPHA_NUMERIC_CHARACTERS.get(a2).intValue()) + id.substring(2);
            };

            return new Pair<>(
                id -> mapper.apply(1, id), // Inbound
                id -> mapper.apply(-1, id) // Outbound
            );
        });

        return outbound ? gatewayIdMappers.value.apply(assetId) : gatewayIdMappers.key.apply(assetId);
    }
}
