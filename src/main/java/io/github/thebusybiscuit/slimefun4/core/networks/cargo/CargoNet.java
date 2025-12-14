package io.github.thebusybiscuit.slimefun4.core.networks.cargo;

import io.github.bakedlibs.dough.common.CommonPatterns;
import io.github.thebusybiscuit.slimefun4.api.network.Network;
import io.github.thebusybiscuit.slimefun4.api.network.NetworkComponent;
import io.github.thebusybiscuit.slimefun4.core.attributes.HologramOwner;
import io.github.thebusybiscuit.slimefun4.implementation.Slimefun;
import me.mrCookieSlime.Slimefun.api.BlockStorage;

import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.logging.Level;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import org.bukkit.Location;
import org.bukkit.block.Block;
import org.bukkit.inventory.ItemStack;

/**
 * The {@link CargoNet} is a type of {@link Network} which deals with {@link ItemStack} transportation.
 */
public class CargoNet extends AbstractItemNetwork implements HologramOwner {

    private static final int RANGE = 5;

    private final Set<Location> inputNodes = new HashSet<>();
    private final Set<Location> outputNodes = new HashSet<>();

    protected final Map<Location, Integer> roundRobin = new HashMap<>();
    private int tickDelayThreshold = 0;

    public static @Nullable CargoNet getNetworkFromLocation(@Nonnull Location l) {
        return Slimefun.getNetworkManager()
                .getNetworkFromLocation(l, CargoNet.class)
                .orElse(null);
    }

    public static @Nonnull CargoNet getNetworkFromLocationOrCreate(@Nonnull Location l) {
        Optional<CargoNet> cargoNetwork = Slimefun.getNetworkManager().getNetworkFromLocation(l, CargoNet.class);

        if (cargoNetwork.isPresent()) {
            return cargoNetwork.get();
        } else {
            CargoNet network = new CargoNet(l);
            Slimefun.getNetworkManager().registerNetwork(network);
            return network;
        }
    }

    protected CargoNet(@Nonnull Location l) {
        super(l);
    }

    @Override
    public String getId() {
        return "CARGO_NETWORK";
    }

    @Override
    public int getRange() {
        return RANGE;
    }

    @Override
    public NetworkComponent classifyLocation(@Nonnull Location l) {
        String id = BlockStorage.checkID(l);

        if (id == null) {
            return null;
        }

        return switch (id) {
            case "CARGO_MANAGER" -> NetworkComponent.REGULATOR;
            case "CARGO_NODE" -> NetworkComponent.CONNECTOR;
            case "CARGO_NODE_INPUT", "CARGO_NODE_OUTPUT", "CARGO_NODE_OUTPUT_ADVANCED" -> NetworkComponent.TERMINUS;
            default -> null;
        };
    }

    @Override
    public void onClassificationChange(Location l, NetworkComponent from, NetworkComponent to) {
        connectorCache.remove(l);

        if (from == NetworkComponent.TERMINUS) {
            inputNodes.remove(l);
            outputNodes.remove(l);
        }

        if (to == NetworkComponent.TERMINUS) {
            String id = BlockStorage.checkID(l);
            if (id != null) {
                switch (id) {
                    case "CARGO_NODE_INPUT" -> inputNodes.add(l);
                    case "CARGO_NODE_OUTPUT", "CARGO_NODE_OUTPUT_ADVANCED" -> outputNodes.add(l);
                    default -> {}
                }
            }
        }
    }

    public void tick(@Nonnull Block b) {
        if (!regulator.equals(b.getLocation())) {
            updateHologram(b, "&4Multiple Cargo Managers found"); 
            return;
        }

        super.tick();

        if (connectorNodes.isEmpty() && terminusNodes.isEmpty()) {
            updateHologram(b, "&cNo Cargo Nodes found");
        } else {
            updateHologram(b, "&7Status: &a&lConnected");

            if (tickDelayThreshold < Slimefun.getCfg().getInt("networks.cargo-ticker-delay")) {
                tickDelayThreshold++;
                return;
            }

            tickDelayThreshold = 0;

            Map<Location, Integer> inputs = mapInputNodes();
            Map<Integer, List<Location>> outputs = mapOutputNodes();

            if (BlockStorage.getLocationInfo(b.getLocation(), "visualizer") == null) {
                display();
            }

            Slimefun.runSync(() -> {
                if (!BlockStorage.check(b, "CARGO_MANAGER")) {
                    return;
                }

                Slimefun.getProfiler().scheduleEntries(inputs.size() + 1);
                new CargoNetworkTask(this, inputs, outputs).run();
            });
        }
    }

    private @Nonnull Map<Location, Integer> mapInputNodes() {
        Map<Location, Integer> inputs = new HashMap<>();

        for (Location node : inputNodes) {
            int frequency = getFrequency(node);

            if (frequency >= 0 && frequency < 16) {
                inputs.put(node, frequency);
            }
        }

        return inputs;
    }

    private @Nonnull Map<Integer, List<Location>> mapOutputNodes() {
        Map<Integer, List<Location>> output = new HashMap<>();

        List<Location> list = new LinkedList<>();
        int lastFrequency = -1;

        for (Location node : outputNodes) {
            int frequency = getFrequency(node);
            if (frequency == -1) {
                continue;
            }

            if (frequency != lastFrequency && lastFrequency != -1) {
                output.merge(lastFrequency, list, (prev, next) -> {
                    prev.addAll(next);
                    return prev;
                });

                list = new LinkedList<>();
            }

            list.add(node);
            lastFrequency = frequency;
        }

        if (!list.isEmpty()) {
            output.merge(lastFrequency, list, (prev, next) -> {
                prev.addAll(next);
                return prev;
            });
        }

        return output;
    }

    /**
     * This method returns the frequency a given node is set to.
     */
    private static int getFrequency(@Nonnull Location node) {
        if (!BlockStorage.hasBlockInfo(node)) {
            return -1;
        }

        String frequency = BlockStorage.getLocationInfo(node, "frequency");

        if (frequency == null) {
            return -1;
        } else if (!CommonPatterns.NUMERIC.matcher(frequency).matches()) {
            // Logging error standar
             Slimefun.logger().log(Level.SEVERE, "Failed to parse a Cargo Node Frequency ({0}, {1}, {2}, {3}): {4}",
                    new Object[] {
                        node.getWorld().getName(),
                        node.getBlockX(),
                        node.getBlockY(),
                        node.getBlockZ(),
                        frequency
                    });
            return -1;
        } else {
            return Integer.parseInt(frequency);
        }
    }
}