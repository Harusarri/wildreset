package kr.harusarri.wildreset;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public final class WildResetConfig {
    public static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static volatile WildResetConfig INSTANCE = new WildResetConfig();

    public boolean enabled = true;
    public String language = "auto";
    public String dimension = "minecraft:overworld";
    public int spawnCenterX = 0;
    public int spawnCenterZ = 0;
    public int cuboidRadiusBlocks = 2500;
    public int protectedBufferHaloChunks = 1;
    public int quarantineOuterBufferChunks = 10;

    public boolean newbieWarningEnabled = true;
    public boolean newbieWarningActionBarEnabled = true;
    public boolean newbieWarningChatEnabled = true;
    public int newbieWarningMaxCount = 4;
    public int newbieWarningCooldownSeconds = 2;

    public int chestProtectionHours = 72;
    public boolean convertDoubleChestsToSingle = true;
    public boolean expireUnclaimedChestsNextSeason = true;
    public boolean evacuateTamedDogsAndCats = true;

    public boolean oreRandomizerEnabled = true;
    public int oreRandomizerMaxY = 16;
    public double lapisToDiamondChance = 0.05;
    public double redstoneToDiamondChance = 0.015;

    public int workerThreads = 4;
    public int mainThreadTickBudgetMs = 12;
    public int countdownSeconds = 3;

    public Messages messages = new Messages();

    public static final class Messages {
        public String newbieBlockPlaceActionBar =
            "<red>[스폰 초기화 구역]</red> <gray>비보호 구역(반경 <radius>m)은 정기 초기화됩니다. (<count>/<max>)</gray>";
        public String newbieBlockPlaceChat =
            "<red>[경고]</red> <gray>스폰 반경 <radius>m 비보호 구역은 정기 초기화됩니다. 영구 건축은 보호구역을 이용해 주세요. (<count>/<max>)</gray>";

        public String bossBarCountdown =
            "<gradient:#ff5555:#ffaa00><bold>[야생 초기화 예고]</bold></gradient> <yellow><seconds>초 후 스폰 야생 비보호 구역이 초기화됩니다! (외곽 +<buffer>청크 접근 제한)</yellow>";
        public String bossBarStaging =
            "<gradient:#55ffff:#55aaff><bold>[야생 초기화 준비 중]</bold></gradient> <white>멀티스레드 백그라운드 청크 조립 중... <aqua><progress>%</aqua> (<done>/<total> 청크)</white>";
        public String bossBarCommitting =
            "<gradient:#55ff55:#ffff55><bold>[야생 초기화 적용 중]</bold></gradient> <white>무중단 청크 덮어쓰기 및 지상 이전 중... <aqua><progress>%</aqua> (<done>/<total> 청크)</white>";
        public String bossBarComplete =
            "<green><bold>[야생 초기화 완료]</bold></green> <white>스폰 야생 비보호 구역(<aqua><total>개 청크</aqua>)이 깨끗하게 초기화되었습니다! (상자 <yellow><chests>개</yellow>, 펫 <yellow><pets>마리</yellow> 지상 이전)</white>";

        public String chatCountdownStart =
            "<gold><bold>[야생 초기화]</bold></gold> <yellow><seconds>초 후 스폰 중심 반경 <white><radius>블록</white> 비보호 야생 구역 무중단 초기화가 시작됩니다. (YAWP 보호 구역은 100% 보존됩니다)</yellow>";
        public String chatResetComplete =
            "<green><bold>[야생 초기화 완료]</bold></green> <gray>총 <white><total>개</white> 비보호 청크 초기화 완료 | 상자 <yellow><chests>개</yellow> 단일화 지상 이전(<hours>시간 보호) | 개·고양이 <aqua><pets>마리</aqua> 지상 이전 | 지하 광맥 재변환 <light_purple><ores>개</light_purple> | 소요 시간 <white><elapsed_ms>ms</white></gray>";

        public String quarantineBlockedActionBar =
            "<red><bold>[접근 봉쇄]</bold></red> <yellow>현재 스폰 야생 구역 초기화 작업 중입니다. 완료 후 진입해 주세요! (포탈·엔더진주·이동 일시 제한)</yellow>";
        public String inZoneFreezeActionBar =
            "<aqua><bold>[시간 동결]</bold></aqua> <white>현재 서 있는 청크를 초기화하고 안전한 지상 좌표로 이동합니다...</white>";
        public String inZoneResumedChat =
            "<green><bold>[야생 초기화]</bold></green> <white>주변 지형이 초기화되어 안전한 지상(<aqua>Y=<y></aqua>)으로 이동되었습니다. 보관된 상자는 지상에서 <yellow><hours>시간</yellow> 동안 보호됩니다.</white>";

        public String chestProtectedDenyActionBar =
            "<red><bold>[상자 보호 중]</bold></red> <yellow>초기화로 지상에 이전된 보관함입니다. 주인 외 열람/파괴 불가 (남은 보호 시간: <white><remaining_time></white>)</yellow>";
        public String chestOwnerOpenActionBar =
            "<green><bold>[보관함 보호 중]</bold></green> <gray>본인 소유의 이전 상자입니다. (남은 보호 시간: <white><remaining_time></white>)</gray>";
        public String chestExpiredNoticeActionBar =
            "<green><bold>[보관 기한 만료]</bold></green> <gray>보호 기한이 만료된 방치 상자입니다. 누구나 회수할 수 있습니다.</gray>";
        public String reloadSuccessChat =
            "<green><bold>[WildReset]</bold></green> <white>설정 파일을 다시 불러왔습니다. (중심: <aqua><x>, <z></aqua> | 반경: <aqua><radius>블록</aqua> | 외곽봉쇄: <aqua>+<buffer>청크</aqua>)</white>";
    }

    public static WildResetConfig get() {
        return INSTANCE;
    }

    public static Path configPath() {
        return FabricLoader.getInstance().getConfigDir().resolve("wildreset.json");
    }

    public static synchronized WildResetConfig load() {
        Path path = configPath();
        if (Files.exists(path)) {
            try {
                String raw = Files.readString(path, StandardCharsets.UTF_8);
                WildResetConfig loaded = GSON.fromJson(raw, WildResetConfig.class);
                if (loaded != null) {
                    if (loaded.language == null || loaded.language.isBlank()) {
                        loaded.language = "auto";
                    }
                    if (loaded.messages == null) {
                        loaded.messages = new Messages();
                    } else if (loaded.messages.chestOwnerOpenActionBar == null) {
                        loaded.messages.chestOwnerOpenActionBar = new Messages().chestOwnerOpenActionBar;
                    }
                    INSTANCE = loaded;
                    save();
                    return INSTANCE;
                }
            } catch (Exception e) {
                WildResetMod.LOGGER.error("[WildReset] Failed to load config/wildreset.json, keeping defaults", e);
            }
        }
        INSTANCE = new WildResetConfig();
        save();
        return INSTANCE;
    }

    public static synchronized void save() {
        Path path = configPath();
        try {
            Files.createDirectories(path.getParent());
            Files.writeString(path, GSON.toJson(INSTANCE) + "\n", StandardCharsets.UTF_8);
        } catch (IOException e) {
            WildResetMod.LOGGER.error("[WildReset] Failed to save config/wildreset.json", e);
        }
    }
}
