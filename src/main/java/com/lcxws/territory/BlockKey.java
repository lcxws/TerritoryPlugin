package com.lcxws.territory;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;

import java.util.Objects;

/**
 * 方块坐标键：世界名 + x/y/z。
 * 用于交互链接的源方块索引与 YAML 持久化，不持有 World/Block 引用，避免区块被长期加载。
 */
public final class BlockKey {

    private final String world;
    private final int x;
    private final int y;
    private final int z;

    private BlockKey(String world, int x, int y, int z) {
        this.world = Objects.requireNonNull(world, "world");
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public static BlockKey of(Block block) {
        return new BlockKey(block.getWorld().getName(), block.getX(), block.getY(), block.getZ());
    }

    public static BlockKey of(Location loc) {
        return new BlockKey(loc.getWorld().getName(), loc.getBlockX(), loc.getBlockY(), loc.getBlockZ());
    }

    public static BlockKey of(String world, int x, int y, int z) {
        return new BlockKey(world, x, y, z);
    }

    /** 反序列化；格式非法返回 null */
    public static BlockKey parse(String text) {
        if (text == null) return null;
        String[] parts = text.split(":", 4);
        if (parts.length != 4) return null;
        try {
            return new BlockKey(parts[0], Integer.parseInt(parts[1]),
                    Integer.parseInt(parts[2]), Integer.parseInt(parts[3]));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    public String getWorld() {
        return world;
    }

    public int getX() {
        return x;
    }

    public int getY() {
        return y;
    }

    public int getZ() {
        return z;
    }

    public int getChunkX() {
        return x >> 4;
    }

    public int getChunkZ() {
        return z >> 4;
    }

    public boolean isInChunk(int chunkX, int chunkZ) {
        return getChunkX() == chunkX && getChunkZ() == chunkZ;
    }

    /** 世界未加载时返回 null（不会主动加载世界） */
    public Location toLocation() {
        World w = Bukkit.getWorld(world);
        return w != null ? new Location(w, x, y, z) : null;
    }

    public String serialize() {
        return world + ":" + x + ":" + y + ":" + z;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof BlockKey other)) return false;
        return x == other.x && y == other.y && z == other.z && world.equals(other.world);
    }

    @Override
    public int hashCode() {
        return Objects.hash(world, x, y, z);
    }

    @Override
    public String toString() {
        return serialize();
    }
}
