package net.runelite.client.plugins.microbot.bga.autoherbiboar;

import net.runelite.api.TileObject;
import net.runelite.api.coords.WorldPoint;
import net.runelite.client.plugins.herbiboars.HerbiboarPlugin;
import net.runelite.client.plugins.herbiboars.HerbiboarSearchSpot;
import net.runelite.client.plugins.microbot.Microbot;
import net.runelite.client.plugins.microbot.Script;
import net.runelite.client.plugins.microbot.util.gameobject.Rs2GameObject;
import net.runelite.client.plugins.microbot.util.npc.Rs2Npc;
import net.runelite.client.plugins.microbot.util.npc.Rs2NpcModel;
import net.runelite.client.plugins.microbot.util.player.Rs2Player;
import net.runelite.client.plugins.microbot.util.walker.Rs2Walker;
import java.util.List;
import java.util.concurrent.TimeUnit;

public class AutoHerbiboarScript extends Script {
    private HerbiboarPlugin herbiboarPlugin;
    private AutoHerbiboarState state = AutoHerbiboarState.START;
    private boolean attackedTunnel;
    public void setHerbiboarPlugin(HerbiboarPlugin herbiboarPlugin){this.herbiboarPlugin=herbiboarPlugin;}
    public boolean run(AutoHerbiboarConfig config) {
        Microbot.enableAutoRunOn = false;
        mainScheduledFuture = scheduledExecutorService.scheduleWithFixedDelay(() -> {
            try {
                if (!Microbot.isLoggedIn()) return;
                if (!super.run()) return;
                if (herbiboarPlugin == null) return;
                switch (state) {
                    case START:
                        if (Rs2Player.isMoving() || Rs2Player.isInteracting() || Rs2Player.isAnimating()) return;
                        if (herbiboarPlugin.getCurrentGroup() == null) {
                            TileObject start = herbiboarPlugin.getStarts().values().stream().findFirst().orElse(null);
                            if (start != null) {
                                WorldPoint loc = start.getWorldLocation();
                                if (Rs2Player.getWorldLocation().distanceTo(loc) <= 20) Rs2GameObject.interact(start, "Search", true);
                                else Rs2Walker.walkTo(loc);
                            }
                        } else state = AutoHerbiboarState.TRAIL;
                        break;
                    case TRAIL:
                        if (herbiboarPlugin.getFinishId() > 0) { state = AutoHerbiboarState.TUNNEL; break; }
                        if (Rs2Player.isMoving() || Rs2Player.isInteracting() || Rs2Player.isAnimating()) return;
                        List<HerbiboarSearchSpot> path = herbiboarPlugin.getCurrentPath();
                        if (!path.isEmpty()) {
                            WorldPoint loc = path.get(path.size() - 1).getLocation();
                            TileObject object = herbiboarPlugin.getTrailObjects().get(loc);
                            if (Rs2Player.getWorldLocation().distanceTo(loc) <= 20) Rs2GameObject.interact(object, "Search", true);
                            else Rs2Walker.walkTo(loc);
                        }
                        break;
                    case TUNNEL:
                        if (Rs2Player.isAnimating() || Rs2Player.isInteracting()) return;
                        if (!attackedTunnel) {
                            int finishId = herbiboarPlugin.getFinishId();
                            if (finishId > 0) {
                                WorldPoint finishLoc = herbiboarPlugin.getEndLocations().get(finishId - 1);
                                TileObject tunnel = herbiboarPlugin.getTunnels().get(finishLoc);
                                int dist = Rs2Player.getWorldLocation().distanceTo(finishLoc);
                                if (dist <= 8) {
                                    Rs2GameObject.interact(tunnel, "Search", true);
                                    Rs2GameObject.interact(tunnel, "Attack", true);
                                    attackedTunnel = true;
                                } else if (!Rs2Player.isMoving()) Rs2Walker.walkTo(finishLoc);
                            }
                        } else {
                            Rs2NpcModel herb = Rs2Npc.getNpc("Herbiboar");
                            if (herb != null) state = AutoHerbiboarState.HARVEST;
                        }
                        break;
                    case HARVEST:
                        if (Rs2Player.isMoving() || Rs2Player.isInteracting() || Rs2Player.isAnimating()) return;
                        Rs2NpcModel herb = Rs2Npc.getNpc("Herbiboar");
                        if (herb != null) {
                            WorldPoint loc = herb.getWorldLocation();
                            if (Rs2Player.getWorldLocation().distanceTo(loc) <= 20) {
                                Rs2Npc.interact(herb, "Harvest");
                                try { Thread.sleep(2000); } catch (InterruptedException ignored) {}
                                TileObject start = herbiboarPlugin.getStarts().values().stream().findFirst().orElse(null);
                                if (start != null) Rs2Walker.walkTo(start.getWorldLocation());
                                attackedTunnel = false;
                                state = AutoHerbiboarState.START;
                            }
                        }
                        break;
                }
            } catch (Exception ex) {
            }
        }, 0, 1000, TimeUnit.MILLISECONDS);
        return true;
    }
    @Override
    public void shutdown() {
        super.shutdown();
    }
}
