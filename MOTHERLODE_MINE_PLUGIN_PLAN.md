# Motherlode Mine State-Based Plugin Plan

## Table of Contents
1. [Overview](#overview)
2. [Core Architecture](#core-architecture)
3. [State Machine Design](#state-machine-design)
4. [Component Structure](#component-structure)
5. [Configuration System](#configuration-system)
6. [Event Management](#event-management)
7. [Error Handling & Recovery](#error-handling--recovery)
8. [Performance Optimization](#performance-optimization)
9. [Implementation Timeline](#implementation-timeline)

## Overview

This document outlines a comprehensive state-based plugin plan for the Motherlode Mine (MLM) in Old School RuneScape. The design emphasizes modularity, maintainability, and robust state management while providing both automation capabilities and helpful overlays for manual play.

### Key Design Principles
- **Clear State Separation**: Each state has distinct responsibilities and clear entry/exit conditions
- **Robust Error Handling**: Comprehensive error recovery with fallback mechanisms
- **Modular Architecture**: Components are loosely coupled and easily testable
- **Performance Focused**: Efficient resource usage with minimal game impact
- **User-Centric**: Flexible configuration with intuitive defaults

## Core Architecture

### Package Structure
```
net.runelite.client.plugins.motherlode/
├── core/
│   ├── MotherloadeMinePlugin.java           # Main plugin class
│   ├── MotherloadeMineScript.java           # Core automation logic
│   └── MotherloadeMineConfig.java           # Configuration interface
├── states/
│   ├── StateManager.java                    # State management system
│   ├── MiningState.java                     # Mining logic
│   ├── DepositState.java                    # Hopper deposit logic
│   ├── SackState.java                       # Sack management
│   ├── RepairState.java                     # Waterwheel repair
│   ├── BankingState.java                    # Banking operations
│   └── IdleState.java                       # Idle/setup state
├── models/
│   ├── MLMStatus.java                       # Status enum
│   ├── MiningSpot.java                      # Mining location data
│   ├── PlayerState.java                     # Player state tracking
│   └── SessionStats.java                    # Session statistics
├── services/
│   ├── LocationService.java                 # Location management
│   ├── InventoryService.java                # Inventory operations
│   ├── ValidationService.java               # State validation
│   └── AntibanService.java                  # Anti-detection logic
├── overlays/
│   ├── MotherloadeMineOverlay.java          # Main overlay
│   ├── MiningOverlay.java                   # Mining-specific overlay
│   ├── StatusOverlay.java                   # Status information
│   └── StatisticsOverlay.java               # Performance stats
└── utils/
    ├── MLMConstants.java                    # Game constants
    ├── CoordinateHelper.java               # Location utilities
    └── TimeUtils.java                       # Timing utilities
```

## State Machine Design

### Primary States

#### 1. INITIALIZING
**Purpose**: Setup and validation before starting operations
- Validate player location (must be in MLM)
- Check equipment requirements (pickaxe)
- Verify inventory setup
- Initialize session tracking
- Validate configuration settings

**Entry Conditions**: Plugin startup or reset
**Exit Conditions**: All validations pass → IDLE, validation fails → ERROR

#### 2. IDLE
**Purpose**: Decision-making and route planning
- Analyze current inventory state
- Determine next action based on priority queue
- Handle dynamic configuration changes
- Perform periodic health checks

**Entry Conditions**: Successful initialization or task completion
**Exit Conditions**: Action determined → appropriate state

#### 3. MINING
**Purpose**: Active ore vein mining
- Find optimal mining veins based on configuration
- Handle vein depletion and switching
- Monitor inventory space
- Apply anti-detection behaviors

**Sub-states**:
- `SEEKING_VEIN`: Looking for available vein
- `MINING_ACTIVE`: Currently mining a vein
- `VEIN_DEPLETED`: Handling depleted vein

**Entry Conditions**: Inventory has space, in mining area
**Exit Conditions**: Inventory full → DEPOSIT_HOPPER, no veins → repositioning

#### 4. DEPOSIT_HOPPER
**Purpose**: Depositing pay-dirt into hopper
- Navigate to appropriate hopper (upper/lower floor)
- Handle inventory management (gem bag, etc.)
- Monitor sack capacity
- Trigger waterwheel repair if needed

**Entry Conditions**: Has pay-dirt to deposit
**Exit Conditions**: Pay-dirt deposited → MINING or EMPTY_SACK

#### 5. EMPTY_SACK
**Purpose**: Collecting ores from sack
- Navigate to sack location
- Collect ores in batches
- Handle banking/deposit operations
- Track valuable drops (nuggets, gems)

**Entry Conditions**: Sack is full or threshold reached
**Exit Conditions**: Sack empty → MINING

#### 6. REPAIR_WATERWHEEL
**Purpose**: Fixing broken waterwheel struts
- Locate broken struts
- Obtain hammer if needed
- Perform repair operations
- Clean up tools

**Entry Conditions**: Struts detected broken
**Exit Conditions**: Repairs complete → previous state

#### 7. BANKING
**Purpose**: Banking operations and resupply
- Deposit ores and gems
- Resupply consumables
- Equipment maintenance
- Inventory reorganization

**Entry Conditions**: Banking threshold reached or forced banking
**Exit Conditions**: Banking complete → MINING

#### 8. ERROR
**Purpose**: Error handling and recovery
- Log error details
- Attempt automatic recovery
- Provide user notifications
- Safe plugin shutdown if critical

**Entry Conditions**: Unrecoverable error detected
**Exit Conditions**: Recovery successful → IDLE, critical error → SHUTDOWN

### State Transition Matrix

| From State | To State | Trigger Condition |
|------------|----------|-------------------|
| INITIALIZING | IDLE | All validations pass |
| INITIALIZING | ERROR | Validation failure |
| IDLE | MINING | Ready to mine |
| IDLE | EMPTY_SACK | Sack full |
| IDLE | BANKING | Banking needed |
| IDLE | REPAIR_WATERWHEEL | Repairs needed |
| MINING | DEPOSIT_HOPPER | Inventory full |
| MINING | IDLE | Need repositioning |
| DEPOSIT_HOPPER | MINING | Deposit complete, space available |
| DEPOSIT_HOPPER | EMPTY_SACK | Sack threshold reached |
| EMPTY_SACK | MINING | Sack empty |
| EMPTY_SACK | BANKING | Banking threshold reached |
| REPAIR_WATERWHEEL | Previous State | Repairs complete |
| BANKING | MINING | Banking complete |
| ERROR | IDLE | Recovery successful |

## Component Structure

### StateManager.java
```java
public class StateManager {
    private MLMStatus currentState = MLMStatus.INITIALIZING;
    private MLMStatus previousState = null;
    private final Map<MLMStatus, State> stateImplementations;
    private final ValidationService validator;
    private final SessionStats sessionStats;
    
    public StateTransitionResult executeCurrentState() {
        State state = stateImplementations.get(currentState);
        StateTransitionResult result = state.execute();
        
        if (result.shouldTransition()) {
            transitionTo(result.getNextState(), result.getReason());
        }
        
        return result;
    }
    
    private void transitionTo(MLMStatus newState, String reason) {
        if (validator.canTransition(currentState, newState)) {
            previousState = currentState;
            currentState = newState;
            sessionStats.recordStateTransition(previousState, currentState, reason);
        }
    }
}
```

### Base State Interface
```java
public interface State {
    StateTransitionResult execute();
    boolean canEnter(MLMStatus fromState);
    void onEnter(MLMStatus fromState);
    void onExit(MLMStatus toState);
    String getStateName();
    Priority getPriority();
}
```

### MiningState.java Implementation
```java
@Slf4j
public class MiningState implements State {
    private final LocationService locationService;
    private final InventoryService inventoryService;
    private final AntibanService antibanService;
    private final MotherloadeMineConfig config;
    
    @Override
    public StateTransitionResult execute() {
        // Validate we can still mine
        if (!canContinueMining()) {
            return StateTransitionResult.transition(MLMStatus.IDLE, "Cannot continue mining");
        }
        
        // Check inventory space
        if (inventoryService.isInventoryFull() && inventoryService.hasPayDirt()) {
            return StateTransitionResult.transition(MLMStatus.DEPOSIT_HOPPER, "Inventory full");
        }
        
        // Find and mine vein
        WallObject vein = findOptimalVein();
        if (vein == null) {
            return handleNoVeinsAvailable();
        }
        
        boolean miningSuccess = attemptMining(vein);
        if (miningSuccess) {
            antibanService.performRandomizedDelay();
            return StateTransitionResult.stay("Mining in progress");
        }
        
        return StateTransitionResult.stay("Mining attempt failed, retrying");
    }
    
    private WallObject findOptimalVein() {
        List<WallObject> availableVeins = locationService.getAvailableVeins(
            config.getMiningArea(),
            config.isAntiCrashEnabled()
        );
        
        return availableVeins.stream()
            .filter(this::isVeinAccessible)
            .min(Comparator.comparing(this::calculateVeinScore))
            .orElse(null);
    }
    
    private int calculateVeinScore(WallObject vein) {
        int distance = Rs2Player.getWorldLocation().distanceTo(vein.getWorldLocation());
        int crowdedness = locationService.getPlayerCountNear(vein.getWorldLocation(), 2);
        return distance + (crowdedness * 10); // Avoid crowded areas
    }
}
```

## Configuration System

### Enhanced Configuration Options
```java
public interface MotherloadeMineConfig extends Config {
    
    // === GENERAL SETTINGS ===
    @ConfigItem(name = "Mining Strategy")
    default MiningStrategy getMiningStrategy() { return MiningStrategy.BALANCED; }
    
    @ConfigItem(name = "Mining Area Priority")
    default List<MiningArea> getAreaPriority() { 
        return Arrays.asList(MiningArea.WEST_UPPER, MiningArea.EAST_UPPER); 
    }
    
    @ConfigItem(name = "Anti-Crash Enabled")
    default boolean isAntiCrashEnabled() { return true; }
    
    @ConfigItem(name = "Max Players Per Vein")
    default int getMaxPlayersPerVein() { return 1; }
    
    // === AUTOMATION SETTINGS ===
    @ConfigItem(name = "Auto Repair Waterwheel")
    default boolean isAutoRepairEnabled() { return true; }
    
    @ConfigItem(name = "Sack Empty Threshold")
    @Range(min = 50, max = 100)
    default int getSackEmptyThreshold() { return 90; }
    
    @ConfigItem(name = "Banking Frequency")
    default BankingFrequency getBankingFrequency() { return BankingFrequency.WHEN_FULL; }
    
    // === PERFORMANCE SETTINGS ===
    @ConfigItem(name = "Tick Delay Range")
    default TickRange getTickDelayRange() { return new TickRange(1, 3); }
    
    @ConfigItem(name = "Antiban Intensity")
    default AntibanIntensity getAntibanIntensity() { return AntibanIntensity.MEDIUM; }
    
    // === OVERLAY SETTINGS ===
    @ConfigItem(name = "Show Mining Overlay")
    default boolean isShowMiningOverlay() { return true; }
    
    @ConfigItem(name = "Show Statistics")
    default boolean isShowStatistics() { return true; }
    
    @ConfigItem(name = "Overlay Position")
    default OverlayPosition getOverlayPosition() { return OverlayPosition.TOP_LEFT; }
    
    // === INVENTORY MANAGEMENT ===
    @ConfigItem(name = "Use Gem Bag")
    default boolean isUseGemBag() { return true; }
    
    @ConfigItem(name = "Keep Items")
    default String getKeepItems() { return "pickaxe,gem bag,hammer"; }
    
    @ConfigItem(name = "Drop Worthless Gems")
    default boolean isDropWorthlessGems() { return false; }
}
```

### Configuration Validation
```java
@Component
public class ConfigValidator {
    
    public ValidationResult validate(MotherloadeMineConfig config) {
        List<String> errors = new ArrayList<>();
        
        // Validate area accessibility
        if (config.getAreaPriority().contains(MiningArea.WEST_UPPER) && 
            !hasUpperFloorAccess()) {
            errors.add("Upper floor selected but not accessible");
        }
        
        // Validate thresholds
        if (config.getSackEmptyThreshold() < 50) {
            errors.add("Sack threshold too low, may cause inefficiency");
        }
        
        // Validate inventory setup
        if (config.isUseGemBag() && !hasGemBag()) {
            errors.add("Gem bag enabled but not available");
        }
        
        return new ValidationResult(errors.isEmpty(), errors);
    }
}
```

## Event Management

### Event-Driven State Updates
```java
@Subscribe
public void onGameObjectSpawned(GameObjectSpawned event) {
    GameObject obj = event.getGameObject();
    
    if (MLMConstants.BROKEN_STRUT_IDS.contains(obj.getId())) {
        sessionStats.incrementBrokenStruts();
        
        if (config.isAutoRepairEnabled() && 
            getBrokenStrutCount() >= config.getRepairThreshold()) {
            stateManager.requestStateTransition(MLMStatus.REPAIR_WATERWHEEL, 
                "Automatic repair triggered");
        }
    }
}

@Subscribe
public void onItemContainerChanged(ItemContainerChanged event) {
    if (event.getContainerId() == InventoryID.INVENTORY.getId()) {
        inventoryService.updateInventorySnapshot();
        
        // Check for valuable drops
        Set<ItemStack> newItems = inventoryService.getNewItems();
        newItems.stream()
            .filter(item -> MLMConstants.VALUABLE_ITEMS.contains(item.getId()))
            .forEach(sessionStats::recordValuableDrop);
    }
}

@Subscribe
public void onVarbitChanged(VarbitChanged event) {
    if (event.getVarbitId() == VarbitID.MOTHERLODE_SACK_TRANSMIT) {
        int sackAmount = event.getValue();
        sessionStats.updateSackAmount(sackAmount);
        
        if (sackAmount >= (getMaxSackSize() * config.getSackEmptyThreshold() / 100)) {
            stateManager.requestStateTransition(MLMStatus.EMPTY_SACK, 
                "Sack threshold reached");
        }
    }
}
```

## Error Handling & Recovery

### Comprehensive Error Management
```java
public class ErrorRecoveryService {
    private final Map<ErrorType, RecoveryStrategy> recoveryStrategies;
    private int consecutiveErrors = 0;
    private static final int MAX_CONSECUTIVE_ERRORS = 5;
    
    public RecoveryResult handleError(Exception error, MLMStatus currentState) {
        ErrorType errorType = classifyError(error);
        consecutiveErrors++;
        
        if (consecutiveErrors >= MAX_CONSECUTIVE_ERRORS) {
            return RecoveryResult.shutdown("Too many consecutive errors");
        }
        
        RecoveryStrategy strategy = recoveryStrategies.get(errorType);
        return strategy.attempt(error, currentState);
    }
    
    private ErrorType classifyError(Exception error) {
        if (error instanceof PathfindingException) return ErrorType.NAVIGATION;
        if (error instanceof InteractionException) return ErrorType.INTERACTION;
        if (error instanceof InventoryException) return ErrorType.INVENTORY;
        if (error instanceof LocationException) return ErrorType.LOCATION;
        return ErrorType.UNKNOWN;
    }
}

// Recovery Strategies
public class NavigationRecoveryStrategy implements RecoveryStrategy {
    @Override
    public RecoveryResult attempt(Exception error, MLMStatus currentState) {
        // Try alternative pathfinding
        if (Rs2Walker.canReach(getAlternativeLocation())) {
            return RecoveryResult.success("Alternative path found");
        }
        
        // Reset to safe location
        if (teleportToSafeLocation()) {
            return RecoveryResult.success("Teleported to safety");
        }
        
        return RecoveryResult.failure("Navigation recovery failed");
    }
}
```

## Performance Optimization

### Efficient Resource Management
```java
public class PerformanceManager {
    private final CacheManager cacheManager;
    private final ThrottleManager throttleManager;
    
    // Caching frequently accessed data
    @Cacheable(expireAfterWrite = 5, timeUnit = TimeUnit.SECONDS)
    public List<WallObject> getAvailableVeins(MiningArea area) {
        return Rs2GameObject.getWallObjects().stream()
            .filter(obj -> MLMConstants.VEIN_IDS.contains(obj.getId()))
            .filter(obj -> isInArea(obj, area))
            .collect(Collectors.toList());
    }
    
    // Throttling expensive operations
    @Throttled(maxCallsPerSecond = 10)
    public PlayerState analyzePlayerState() {
        return PlayerState.builder()
            .location(Rs2Player.getWorldLocation())
            .inventoryState(inventoryService.getInventorySnapshot())
            .equipment(equipmentService.getEquipmentSnapshot())
            .build();
    }
    
    // Memory management
    public void performMaintenanceCleanup() {
        cacheManager.evictExpiredEntries();
        sessionStats.compactOldData();
        throttleManager.resetCounters();
    }
}
```

### Overlay Performance
```java
public class OptimizedMiningOverlay extends Overlay {
    private static final int RENDER_FREQUENCY = 50; // Every 50ms
    private long lastRender = 0;
    private CachedRenderData cachedData;
    
    @Override
    public Dimension render(Graphics2D graphics) {
        long currentTime = System.currentTimeMillis();
        
        if (currentTime - lastRender < RENDER_FREQUENCY && cachedData != null) {
            renderCachedData(graphics, cachedData);
            return cachedData.getDimensions();
        }
        
        cachedData = generateRenderData();
        lastRender = currentTime;
        
        return renderFreshData(graphics, cachedData);
    }
    
    private CachedRenderData generateRenderData() {
        // Only recalculate when necessary
        return CachedRenderData.builder()
            .veins(getVisibleVeins())
            .playerInfo(getPlayerInfo())
            .sessionStats(getSessionStats())
            .build();
    }
}
```

## Implementation Timeline

### Phase 1: Core Infrastructure (Week 1-2)
- [ ] Set up package structure and base classes
- [ ] Implement StateManager and base State interface
- [ ] Create core service classes (LocationService, InventoryService)
- [ ] Basic configuration system
- [ ] Unit test framework setup

### Phase 2: State Implementation (Week 3-4)
- [ ] Implement INITIALIZING and IDLE states
- [ ] Implement MINING state with vein detection
- [ ] Implement DEPOSIT_HOPPER state
- [ ] Implement EMPTY_SACK state
- [ ] Basic state transition testing

### Phase 3: Advanced Features (Week 5-6)
- [ ] Implement REPAIR_WATERWHEEL state
- [ ] Implement BANKING state
- [ ] Error handling and recovery system
- [ ] Advanced anti-detection features
- [ ] Performance optimization

### Phase 4: User Interface (Week 7)
- [ ] Enhanced overlay system
- [ ] Statistics tracking and display
- [ ] Configuration validation
- [ ] User documentation

### Phase 5: Testing & Polish (Week 8)
- [ ] Comprehensive integration testing
- [ ] Performance benchmarking
- [ ] Bug fixes and optimization
- [ ] Code review and cleanup
- [ ] Final documentation

## Success Metrics

### Functionality Metrics
- State transitions execute within 100ms average
- 99.5% success rate for core operations
- Zero critical errors during normal operation
- Memory usage remains under 50MB

### User Experience Metrics
- Configuration setup completed in under 2 minutes
- Clear visual feedback for all states
- Comprehensive error messages with recovery suggestions
- Performance impact imperceptible during gameplay

### Automation Metrics
- Mining efficiency: 95%+ of theoretical maximum
- Minimal detection risk through varied behaviors
- Robust handling of game updates and changes
- Scalable architecture for future enhancements

This comprehensive plan provides a solid foundation for implementing a robust, maintainable, and efficient Motherlode Mine plugin that excels in both automated and manual gameplay scenarios.