package com.lcxws.territory;

import java.util.UUID;

/**
 * 一条交互链接：右键源方块（按钮/拉杆）时，由插件操作目标方块（门/活板门/栅栏门）。
 * 源方块允许位于领地外；目标方块必须位于链接所属领地内。
 */
public class InteractionLink {

    public enum Mode {
        /** 仅领主与白名单成员可用 */
        TRUSTED("仅信任"),
        /** 任何未被拉黑的玩家都可以用 */
        PUBLIC("公开");

        public final String display;

        Mode(String display) {
            this.display = display;
        }

        public static Mode byName(String name) {
            if (name == null) return TRUSTED;
            try {
                return valueOf(name.trim().toUpperCase());
            } catch (IllegalArgumentException ex) {
                return TRUSTED;
            }
        }
    }

    private final UUID id;
    private UUID territoryId;
    private final BlockKey source;
    private final BlockKey target;
    private Mode mode;
    private int autoCloseSeconds;
    private boolean enabled;
    private final long createdAt;

    public InteractionLink(UUID id, UUID territoryId, BlockKey source, BlockKey target,
                           Mode mode, int autoCloseSeconds, boolean enabled, long createdAt) {
        this.id = id != null ? id : UUID.randomUUID();
        this.territoryId = territoryId;
        this.source = source;
        this.target = target;
        this.mode = mode != null ? mode : Mode.TRUSTED;
        this.autoCloseSeconds = clampAutoClose(autoCloseSeconds);
        this.enabled = enabled;
        this.createdAt = createdAt > 0 ? createdAt : System.currentTimeMillis();
    }

    public static InteractionLink create(UUID territoryId, BlockKey source, BlockKey target,
                                         Mode mode, int autoCloseSeconds) {
        return new InteractionLink(UUID.randomUUID(), territoryId, source, target,
                mode, autoCloseSeconds, true, System.currentTimeMillis());
    }

    public static int clampAutoClose(int seconds) {
        return Math.max(0, Math.min(3600, seconds));
    }

    public UUID getId() {
        return id;
    }

    public UUID getTerritoryId() {
        return territoryId;
    }

    public void setTerritoryId(UUID territoryId) {
        this.territoryId = territoryId;
    }

    public BlockKey getSource() {
        return source;
    }

    public BlockKey getTarget() {
        return target;
    }

    public Mode getMode() {
        return mode;
    }

    public void setMode(Mode mode) {
        this.mode = mode != null ? mode : Mode.TRUSTED;
    }

    public boolean isPublic() {
        return mode == Mode.PUBLIC;
    }

    public int getAutoCloseSeconds() {
        return autoCloseSeconds;
    }

    public void setAutoCloseSeconds(int autoCloseSeconds) {
        this.autoCloseSeconds = clampAutoClose(autoCloseSeconds);
    }

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getCreatedAt() {
        return createdAt;
    }
}
