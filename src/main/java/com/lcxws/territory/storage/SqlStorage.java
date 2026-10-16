package com.lcxws.territory.storage;

import com.lcxws.territory.Module;
import com.lcxws.territory.Territory;
import com.lcxws.territory.TerritoryPlugin;
import com.lcxws.territory.manager.TerritoryManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

import java.io.File;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;

/**
 * SQL 存储（SQLite / MySQL）：
 * - 启动时一次性读入内存，运行时查询仍是 O(1) 内存索引（数据库不参与热路径）
 * - 自动保存只做增量写入：每块脏领地一条 UPDATE/INSERT + 子树重建，事务包裹，不整库重写
 * - SQLite 为默认（零运维）；MySQL 供跨服共享 / 集中管理 / 网页工具浏览
 *
 * 表结构（表名为固定常量，勿与其他插件重名；多服共用数据库时请各用独立 database）：
 *   territory_data(id, owner, owner_name, world, created, has_tp, tp_x..tp_pitch)
 *   territory_chunks(territory_id, chunk_x, chunk_z)
 *   territory_members(territory_id, player, role)      role: W=白名单 B=黑名单
 *   territory_modules(territory_id, module, enabled)
 *   territory_names(uuid, name)
 *
 * 安全说明：本类所有 SQL 语句均以字符串字面量内联在各调用点（无任何拼接与变量透传），
 * 所有来自玩家/数据的值一律通过 PreparedStatement 的 ? 占位符参数化传入。
 */
public class SqlStorage {

    /** 单个领地的不可变快照行（主线程构建，异步线程写库） */
    public static final class TerritoryRow {
        final UUID id;
        final UUID owner;
        final String ownerName;
        final String world;
        final long created;
        final long[] chunks;
        final UUID[] whitelist;
        final UUID[] blacklist;
        final String[] purchased;
        final String[] enabled;
        final boolean hasTp;
        final double tpX, tpY, tpZ, tpYaw, tpPitch;

        private TerritoryRow(UUID id, UUID owner, String ownerName, String world, long created,
                             long[] chunks, UUID[] whitelist, UUID[] blacklist,
                             String[] purchased, String[] enabled,
                             boolean hasTp, double tpX, double tpY, double tpZ, double tpYaw, double tpPitch) {
            this.id = id;
            this.owner = owner;
            this.ownerName = ownerName;
            this.world = world;
            this.created = created;
            this.chunks = chunks;
            this.whitelist = whitelist;
            this.blacklist = blacklist;
            this.purchased = purchased;
            this.enabled = enabled;
            this.hasTp = hasTp;
            this.tpX = tpX;
            this.tpY = tpY;
            this.tpZ = tpZ;
            this.tpYaw = tpYaw;
            this.tpPitch = tpPitch;
        }

        public static TerritoryRow of(Territory t, String ownerName) {
            Location tp = t.getTpPoint();
            return new TerritoryRow(t.getId(), t.getOwner(), ownerName, t.getWorld(), t.getCreated(),
                    t.getChunks().stream().mapToLong(Long::longValue).toArray(),
                    t.getWhitelist().toArray(new UUID[0]),
                    t.getBlacklist().toArray(new UUID[0]),
                    t.getPurchased().stream().map(Enum::name).toArray(String[]::new),
                    t.getEnabled().stream().map(Enum::name).toArray(String[]::new),
                    tp != null,
                    tp != null ? tp.getX() : 0, tp != null ? tp.getY() : 0, tp != null ? tp.getZ() : 0,
                    tp != null ? tp.getYaw() : 0, tp != null ? tp.getPitch() : 0);
        }
    }

    private final TerritoryPlugin plugin;
    private final Object lock = new Object();

    private boolean mysql;
    private Connection conn;

    public SqlStorage(TerritoryPlugin plugin) {
        this.plugin = plugin;
    }

    /* ========================= 连接与建表 ========================= */

    public boolean init() {
        String type = plugin.getConfig().getString("storage.type", "sqlite").toLowerCase(Locale.ROOT);
        mysql = type.equals("mysql");
        try {
            synchronized (lock) {
                if (mysql) {
                    Class.forName("com.mysql.cj.jdbc.Driver");
                } else {
                    Class.forName("org.sqlite.JDBC");
                }
                conn = openConnection();
                createTables();
            }
            plugin.getLogger().info("数据存储已就绪: " + (mysql ? "MySQL" : "SQLite"));
            return true;
        } catch (ClassNotFoundException ex) {
            plugin.getLogger().severe("缺少数据库驱动: " + ex.getMessage());
        } catch (SQLException ex) {
            plugin.getLogger().severe("连接数据库失败: " + ex.getMessage());
        }
        return false;
    }

    private Connection openConnection() throws SQLException {
        if (mysql) {
            Properties props = new Properties();
            props.setProperty("user", plugin.getConfig().getString("storage.mysql.user", "root"));
            props.setProperty("password", plugin.getConfig().getString("storage.mysql.password", ""));
            props.setProperty("useSSL", "false");
            props.setProperty("characterEncoding", "UTF-8");
            props.setProperty("serverTimezone", "UTC");
            props.setProperty("allowPublicKeyRetrieval", "true");
            String host = plugin.getConfig().getString("storage.mysql.host", "localhost");
            if (host == null) host = "localhost";
            // 白名单消毒，防止配置被篡改时向连接串注入额外参数
            host = host.replaceAll("[^A-Za-z0-9._\\-]", "");
            String database = plugin.getConfig().getString("storage.mysql.database", "territory");
            if (database == null) database = "territory";
            database = database.replaceAll("[^A-Za-z0-9_]", "");
            int port = plugin.getConfig().getInt("storage.mysql.port", 3306);
            return DriverManager.getConnection(
                    String.join("", "jdbc:mysql://", host, ":", String.valueOf(port), "/", database), props);
        }
        File file = new File(plugin.getDataFolder(),
                plugin.getConfig().getString("storage.sqlite.file", "territories.db"));
        if (file.getParentFile() != null) file.getParentFile().mkdirs();
        String url = String.join("", "jdbc:sqlite:", file.getAbsolutePath());
        Connection c = DriverManager.getConnection(url);
        try (Statement st = c.createStatement()) {
            st.execute("PRAGMA busy_timeout=5000");
        }
        return c;
    }

    /** 连接可用性检查（MySQL 长连接超时后自动重连）；不可用返回 false */
    private boolean ensureConnection() {
        try {
            if (conn == null || conn.isClosed()) {
                conn = openConnection();
                return true;
            }
            if (mysql && !conn.isValid(3)) {
                try {
                    conn.close();
                } catch (SQLException ignored) {
                }
                conn = openConnection();
            }
            return true;
        } catch (SQLException ex) {
            plugin.getLogger().severe("数据库连接不可用: " + ex.getMessage());
            return false;
        }
    }

    private void createTables() throws SQLException {
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_data (id VARCHAR(36) PRIMARY KEY, owner VARCHAR(36) NOT NULL, owner_name VARCHAR(32), world VARCHAR(64) NOT NULL, created BIGINT NOT NULL, has_tp INT NOT NULL DEFAULT 0, tp_x DOUBLE, tp_y DOUBLE, tp_z DOUBLE, tp_yaw DOUBLE, tp_pitch DOUBLE)");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_chunks (territory_id VARCHAR(36) NOT NULL, chunk_x INT NOT NULL, chunk_z INT NOT NULL, PRIMARY KEY (territory_id, chunk_x, chunk_z))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_members (territory_id VARCHAR(36) NOT NULL, player VARCHAR(36) NOT NULL, role VARCHAR(1) NOT NULL, PRIMARY KEY (territory_id, player, role))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_modules (territory_id VARCHAR(36) NOT NULL, module VARCHAR(32) NOT NULL, enabled INT NOT NULL DEFAULT 0, PRIMARY KEY (territory_id, module))");
            st.executeUpdate("CREATE TABLE IF NOT EXISTS territory_names (uuid VARCHAR(36) PRIMARY KEY, name VARCHAR(32) NOT NULL)");
        }
        // 辅助索引：MySQL 不支持 CREATE INDEX IF NOT EXISTS，已存在时报错直接忽略
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE INDEX territory_idx_owner ON territory_data (owner)");
        } catch (SQLException ignored) {
        }
        try (Statement st = conn.createStatement()) {
            st.executeUpdate("CREATE INDEX territory_idx_chunk ON territory_chunks (chunk_x, chunk_z)");
        } catch (SQLException ignored) {
        }
    }

    /* ========================= 读取 ========================= */

    public void load(List<Territory> out, Map<UUID, String> namesOut) {
        synchronized (lock) {
            if (!ensureConnection()) return;
            try {
                Map<UUID, Territory> map = new HashMap<>();
                try (Statement st = conn.createStatement()) {
                    try (ResultSet rs = st.executeQuery("SELECT id, owner, world, created, has_tp, tp_x, tp_y, tp_z, tp_yaw, tp_pitch FROM territory_data")) {
                        while (rs.next()) {
                            try {
                                UUID id = UUID.fromString(rs.getString("id"));
                                UUID owner = UUID.fromString(rs.getString("owner"));
                                String world = rs.getString("world");
                                if (world == null) continue;
                                Territory t = new Territory(id, owner, world);
                                t.setCreated(rs.getLong("created"));
                                if (rs.getInt("has_tp") == 1) {
                                    World w = Bukkit.getWorld(world);
                                    if (w != null) {
                                        t.setTpPoint(new Location(w,
                                                rs.getDouble("tp_x"), rs.getDouble("tp_y"), rs.getDouble("tp_z"),
                                                (float) rs.getDouble("tp_yaw"), (float) rs.getDouble("tp_pitch")));
                                    }
                                }
                                map.put(id, t);
                            } catch (IllegalArgumentException ex) {
                                plugin.getLogger().warning("跳过一条非法领地记录: " + ex.getMessage());
                            }
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT territory_id, chunk_x, chunk_z FROM territory_chunks")) {
                        while (rs.next()) {
                            Territory t = lookup(map, rs.getString("territory_id"));
                            if (t != null) {
                                t.getChunks().add(TerritoryManager.pack(rs.getInt("chunk_x"), rs.getInt("chunk_z")));
                            }
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT territory_id, player, role FROM territory_members")) {
                        while (rs.next()) {
                            Territory t = lookup(map, rs.getString("territory_id"));
                            if (t == null) continue;
                            try {
                                UUID u = UUID.fromString(rs.getString("player"));
                                if ("W".equals(rs.getString("role"))) {
                                    t.getWhitelist().add(u);
                                } else {
                                    t.getBlacklist().add(u);
                                }
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT territory_id, module, enabled FROM territory_modules")) {
                        while (rs.next()) {
                            Territory t = lookup(map, rs.getString("territory_id"));
                            if (t == null) continue;
                            try {
                                Module m = Module.valueOf(rs.getString("module"));
                                t.getPurchased().add(m);
                                if (rs.getInt("enabled") == 1) t.getEnabled().add(m);
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                    try (ResultSet rs = st.executeQuery("SELECT uuid, name FROM territory_names")) {
                        while (rs.next()) {
                            try {
                                namesOut.put(UUID.fromString(rs.getString("uuid")), rs.getString("name"));
                            } catch (IllegalArgumentException ignored) {
                            }
                        }
                    }
                }
                for (Territory t : map.values()) {
                    if (t.getChunks().isEmpty()) {
                        plugin.getLogger().warning("领地 " + t.getId() + " 没有区块记录，已跳过。");
                        continue;
                    }
                    out.add(t);
                }
            } catch (SQLException ex) {
                plugin.getLogger().severe("读取领地数据失败: " + ex.getMessage());
            }
        }
    }

    private static Territory lookup(Map<UUID, Territory> map, String idStr) {
        try {
            return map.get(UUID.fromString(idStr));
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    /* ========================= 增量写入 ========================= */

    /** 异步线程调用：事务内完成删除 + 脏领地 upsert + 名字 upsert */
    public void save(Collection<TerritoryRow> rows, Set<UUID> removed, Map<UUID, String> names) {
        synchronized (lock) {
            if (!ensureConnection()) return;
            try {
                boolean auto = conn.getAutoCommit();
                conn.setAutoCommit(false);
                try {
                    for (UUID id : removed) deleteTerritory(id);
                    for (TerritoryRow row : rows) {
                        upsertTerritory(row);
                        replaceChunks(row);
                        replaceMembers(row);
                        replaceModules(row);
                    }
                    for (Map.Entry<UUID, String> e : names.entrySet()) {
                        upsertName(e.getKey(), e.getValue());
                    }
                    conn.commit();
                } catch (SQLException ex) {
                    try {
                        conn.rollback();
                    } catch (SQLException ignored) {
                    }
                    plugin.getLogger().severe("写入领地数据失败（已回滚）: " + ex.getMessage());
                } finally {
                    try {
                        conn.setAutoCommit(auto);
                    } catch (SQLException ignored) {
                    }
                }
            } catch (SQLException ex) {
                plugin.getLogger().severe("写入领地数据失败: " + ex.getMessage());
            }
        }
    }

    private void upsertTerritory(TerritoryRow r) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE territory_data SET owner=?, owner_name=?, world=?, created=?, has_tp=?, tp_x=?, tp_y=?, tp_z=?, tp_yaw=?, tp_pitch=? WHERE id=?")) {
            ps.setString(1, r.owner.toString());
            ps.setString(2, r.ownerName);
            ps.setString(3, r.world);
            ps.setLong(4, r.created);
            ps.setInt(5, r.hasTp ? 1 : 0);
            ps.setDouble(6, r.tpX);
            ps.setDouble(7, r.tpY);
            ps.setDouble(8, r.tpZ);
            ps.setDouble(9, r.tpYaw);
            ps.setDouble(10, r.tpPitch);
            ps.setString(11, r.id.toString());
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO territory_data (id, owner, owner_name, world, created, has_tp, tp_x, tp_y, tp_z, tp_yaw, tp_pitch) VALUES (?,?,?,?,?,?,?,?,?,?,?)")) {
            ps.setString(1, r.id.toString());
            ps.setString(2, r.owner.toString());
            ps.setString(3, r.ownerName);
            ps.setString(4, r.world);
            ps.setLong(5, r.created);
            ps.setInt(6, r.hasTp ? 1 : 0);
            ps.setDouble(7, r.tpX);
            ps.setDouble(8, r.tpY);
            ps.setDouble(9, r.tpZ);
            ps.setDouble(10, r.tpYaw);
            ps.setDouble(11, r.tpPitch);
            ps.executeUpdate();
        }
    }

    private void replaceChunks(TerritoryRow r) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM territory_chunks WHERE territory_id=?")) {
            del.setString(1, r.id.toString());
            del.executeUpdate();
        }
        if (r.chunks.length == 0) return;
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO territory_chunks (territory_id, chunk_x, chunk_z) VALUES (?,?,?)")) {
            for (long packed : r.chunks) {
                ins.setString(1, r.id.toString());
                ins.setInt(2, TerritoryManager.unpackX(packed));
                ins.setInt(3, TerritoryManager.unpackZ(packed));
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    private void replaceMembers(TerritoryRow r) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM territory_members WHERE territory_id=?")) {
            del.setString(1, r.id.toString());
            del.executeUpdate();
        }
        if (r.whitelist.length == 0 && r.blacklist.length == 0) return;
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO territory_members (territory_id, player, role) VALUES (?,?,?)")) {
            for (UUID u : r.whitelist) {
                ins.setString(1, r.id.toString());
                ins.setString(2, u.toString());
                ins.setString(3, "W");
                ins.addBatch();
            }
            for (UUID u : r.blacklist) {
                ins.setString(1, r.id.toString());
                ins.setString(2, u.toString());
                ins.setString(3, "B");
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    private void replaceModules(TerritoryRow r) throws SQLException {
        try (PreparedStatement del = conn.prepareStatement("DELETE FROM territory_modules WHERE territory_id=?")) {
            del.setString(1, r.id.toString());
            del.executeUpdate();
        }
        if (r.purchased.length == 0) return;
        try (PreparedStatement ins = conn.prepareStatement("INSERT INTO territory_modules (territory_id, module, enabled) VALUES (?,?,?)")) {
            for (String m : r.purchased) {
                ins.setString(1, r.id.toString());
                ins.setString(2, m);
                ins.setInt(3, contains(r.enabled, m) ? 1 : 0);
                ins.addBatch();
            }
            ins.executeBatch();
        }
    }

    private void deleteTerritory(UUID id) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM territory_chunks WHERE territory_id=?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM territory_members WHERE territory_id=?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM territory_modules WHERE territory_id=?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
        try (PreparedStatement ps = conn.prepareStatement("DELETE FROM territory_data WHERE id=?")) {
            ps.setString(1, id.toString());
            ps.executeUpdate();
        }
    }

    private void upsertName(UUID uuid, String name) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement("UPDATE territory_names SET name=? WHERE uuid=?")) {
            ps.setString(1, name);
            ps.setString(2, uuid.toString());
            if (ps.executeUpdate() > 0) return;
        }
        try (PreparedStatement ps = conn.prepareStatement("INSERT INTO territory_names (uuid, name) VALUES (?,?)")) {
            ps.setString(1, uuid.toString());
            ps.setString(2, name);
            ps.executeUpdate();
        }
    }

    private static boolean contains(String[] arr, String value) {
        for (String s : arr) {
            if (s.equals(value)) return true;
        }
        return false;
    }

    /* ========================= 关闭 ========================= */

    public void close() {
        synchronized (lock) {
            if (conn == null) return;
            try {
                conn.close();
            } catch (SQLException ignored) {
            }
            conn = null;
        }
    }
}
