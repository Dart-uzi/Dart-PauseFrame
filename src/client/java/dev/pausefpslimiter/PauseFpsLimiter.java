package dev.pausefpslimiter;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.PauseScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.Collections;
import java.util.Set;
import java.util.WeakHashMap;

public final class PauseFpsLimiter implements ClientModInitializer {
    public static final String MOD_ID = "pause_fps_limiter";
    public static final String AUTHOR_TOKEN = "Dart_uzi";
    public static final int MIN_FPS = 1;
    public static final int MAX_FPS = 260;
    public static final Config CONFIG = Config.load();

    private static ScreenCategory lastCategory;
    private static long categorySince;
    private static boolean lastFocused = true;
    private static long focusSince;
    private static final Set<Screen> OVERLAY_REGISTERED = Collections.newSetFromMap(new WeakHashMap<>());

    private static int clampFps(int fps) { return Math.max(MIN_FPS, Math.min(MAX_FPS, fps)); }

    @Override
    public void onInitializeClient() {
        ScreenEvents.AFTER_INIT.register((client, screen, scaledWidth, scaledHeight) -> {
            if (OVERLAY_REGISTERED.add(screen)) {
                ScreenEvents.afterExtract(screen).register((screen1, graphics, mouseX, mouseY, tickProgress) ->
                        renderOverlay(screen1, graphics));
            }
        });
    }

    public static Integer getEffectiveFpsLimit(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        if (!CONFIG.enabled) return null;
        boolean focused = client.isWindowActive();
        if (focused != lastFocused) { lastFocused = focused; focusSince = System.nanoTime(); }
        if (CONFIG.unfocusedMode && !focused) {
            if (remainingDelayMs(focusSince, CONFIG.unfocusedDelayMs) > 0) return null;
            return clampFps(CONFIG.unfocusedFps);
        }
        if (screen == null) return null;
        ScreenCategory category = classify(screen);
        updateCategory(category);
        int delay = delayFor(category);
        if (remainingDelayMs(categorySince, delay) > 0) return null;
        if (!CONFIG.isCategoryEnabled(category)) return null;
        return CONFIG.fpsFor(category);
    }

    private static void updateCategory(ScreenCategory category) {
        if (category != lastCategory) { lastCategory = category; categorySince = System.nanoTime(); }
    }

    public static long getRemainingDelayMs(Screen screen) {
        Minecraft client = Minecraft.getInstance();
        if (!CONFIG.enabled) return 0;
        boolean focused = client.isWindowActive();
        if (!focused && CONFIG.unfocusedMode) {
            if (focusSince == 0) focusSince = System.nanoTime();
            return remainingDelayMs(focusSince, CONFIG.unfocusedDelayMs);
        }
        if (screen == null) return 0;
        ScreenCategory category = classify(screen);
        updateCategory(category);
        return remainingDelayMs(categorySince, delayFor(category));
    }

    private static long remainingDelayMs(long since, int delayMs) {
        if (delayMs <= 0 || since == 0) return 0;
        long elapsed = (System.nanoTime() - since) / 1_000_000L;
        return Math.max(0L, delayMs - elapsed);
    }

    private static int delayFor(ScreenCategory c) {
        return switch (c) {
            case PAUSE -> CONFIG.pauseDelayMs;
            case SETTINGS -> CONFIG.settingsDelayMs;
            case CHAT -> CONFIG.chatDelayMs;
            case INVENTORY -> CONFIG.inventoryDelayMs;
            case CONTAINER -> CONFIG.containerDelayMs;
            case MOD -> CONFIG.modDelayMs;
            case OTHER -> CONFIG.otherDelayMs;
        };
    }

    public static boolean shouldSkipWorld() {
        Minecraft client = Minecraft.getInstance();
        return CONFIG.enabled && CONFIG.skipWorldBehindMenu && client.level != null
                && client.gui != null && client.gui.screen() != null;
    }

    public static boolean shouldMuteAudio() {
        Minecraft client = Minecraft.getInstance();
        return CONFIG.enabled && CONFIG.muteAudioOnPause && client.gui != null
                && client.gui.screen() instanceof PauseScreen;
    }

    public static ScreenCategory classify(Screen screen) {
        if (screen instanceof PauseScreen) return ScreenCategory.PAUSE;
        String name = screen.getClass().getName();
        String simple = screen.getClass().getSimpleName().toLowerCase();
        if (simple.contains("chat")) return ScreenCategory.CHAT;
        if (simple.contains("inventory")) return ScreenCategory.INVENTORY;
        if (simple.contains("handled") || simple.contains("container") || simple.contains("merchant")
                || simple.contains("smithing") || simple.contains("loom") || simple.contains("stonecutter")) return ScreenCategory.CONTAINER;
        if (simple.contains("options") || simple.contains("settings") || simple.contains("video")
                || simple.contains("controls") || simple.contains("keybind") || simple.contains("language")
                || simple.contains("accessibility") || simple.contains("pack") || simple.contains("sound")
                || simple.contains("chatoptions") || name.contains("OptionsScreen")) return ScreenCategory.SETTINGS;
        boolean vanilla = name.startsWith("net.minecraft.") || name.startsWith("com.mojang.");
        return vanilla ? ScreenCategory.OTHER : ScreenCategory.MOD;
    }

    private static void renderOverlay(Screen screen, GuiGraphicsExtractor graphics) {
        Minecraft client = Minecraft.getInstance();
        if (!CONFIG.overlayEnabled || !CONFIG.enabled || client.level == null) return;
        long remaining = getRemainingDelayMs(screen);
        Integer limit = getEffectiveFpsLimit(screen);
        if (limit == null && remaining <= 0) return;
        String text = CONFIG.overlayText
                .replace("%fps%", String.valueOf(limit == null ? CONFIG.fpsFor(classify(screen)) : limit))
                .replace("%countdown%", String.format("%.1f", remaining / 1000.0))
                .replace("%mode%", limit == null ? "Waiting" : "Active");
        int width = client.font.width(text);
        int margin = 8;
        int x = switch (CONFIG.overlayCorner) {
            case 1, 3 -> screen.width - width - margin;
            default -> margin;
        };
        int y = switch (CONFIG.overlayCorner) {
            case 2, 3 -> screen.height - client.font.lineHeight - margin;
            default -> margin;
        };
        float scale = CONFIG.overlayScale / 100.0f;
        if (scale != 1.0f) {
            graphics.pose().pushMatrix();
            graphics.pose().scale(scale, scale);
            x = Math.round(x / scale);
            y = Math.round(y / scale);
        }
        int alpha = Math.round(CONFIG.overlayAlpha * 255.0f / 100.0f) ;
        int color = (alpha << 24) | (CONFIG.overlayColor & 0x00FFFFFF);
        graphics.text(client.font, text, x, y, color, true);
        if (scale != 1.0f) graphics.pose().popMatrix();
    }

    public enum ScreenCategory {
        PAUSE("Pause"), SETTINGS("Settings"), CHAT("Chat"), INVENTORY("Inventory"), CONTAINER("Containers"), MOD("Mod screens"), OTHER("Other screens");
        final String label;
        ScreenCategory(String label) { this.label = label; }
    }

    public static final class Config {
        public boolean enabled = true;
        public boolean limitPause = true, limitSettings = true, limitChat = true, limitInventory = true,
                limitContainers = true, limitModScreens = true, limitOtherScreens = true;
        public int pauseFps = 20, settingsFps = 20, chatFps = 20, inventoryFps = 20,
                containerFps = 20, modFps = 20, otherFps = 20;
        public int pauseDelayMs = 600, settingsDelayMs = 600, chatDelayMs = 600, inventoryDelayMs = 600,
                containerDelayMs = 600, modDelayMs = 600, otherDelayMs = 600, unfocusedDelayMs = 300;
        public boolean skipWorldBehindMenu = false;
        public boolean muteAudioOnPause = false;
        public boolean overlayEnabled = true;
        public boolean overlayShowCountdown = true;
        public int overlayCorner = 1;
        public int overlayAlpha = 100;
        public int overlayScale = 100;
        public int overlayColor = 0xFFFFFF;
        public String overlayText = "Dart: PauseFrame: %fps% FPS (%mode%)";
        public boolean unfocusedMode = true;
        public int unfocusedFps = 15;
        private Path file;

        public static Config load() {
            Config c = new Config();
            try {
                c.file = FabricLoader.getInstance().getConfigDir().resolve("pause_fps_limiter.json");
                if (Files.exists(c.file)) {
                    String s = Files.readString(c.file);
                    c.enabled=bool(s,"enabled",c.enabled); c.limitPause=bool(s,"limitPause",c.limitPause); c.limitSettings=bool(s,"limitSettings",c.limitSettings);
                    c.limitChat=bool(s,"limitChat",c.limitChat); c.limitInventory=bool(s,"limitInventory",c.limitInventory); c.limitContainers=bool(s,"limitContainers",c.limitContainers);
                    c.limitModScreens=bool(s,"limitModScreens",c.limitModScreens); c.limitOtherScreens=bool(s,"limitOtherScreens",c.limitOtherScreens);
                    c.pauseFps=integer(s,"pauseFps",c.pauseFps); c.settingsFps=integer(s,"settingsFps",c.settingsFps); c.chatFps=integer(s,"chatFps",c.chatFps);
                    c.inventoryFps=integer(s,"inventoryFps",c.inventoryFps); c.containerFps=integer(s,"containerFps",c.containerFps); c.modFps=integer(s,"modFps",c.modFps); c.otherFps=integer(s,"otherFps",c.otherFps);
                    c.pauseDelayMs=integer(s,"pauseDelayMs",c.pauseDelayMs); c.settingsDelayMs=integer(s,"settingsDelayMs",c.settingsDelayMs); c.chatDelayMs=integer(s,"chatDelayMs",c.chatDelayMs);
                    c.inventoryDelayMs=integer(s,"inventoryDelayMs",c.inventoryDelayMs); c.containerDelayMs=integer(s,"containerDelayMs",c.containerDelayMs); c.modDelayMs=integer(s,"modDelayMs",c.modDelayMs); c.otherDelayMs=integer(s,"otherDelayMs",c.otherDelayMs); c.unfocusedDelayMs=integer(s,"unfocusedDelayMs",c.unfocusedDelayMs);
                    c.skipWorldBehindMenu=bool(s,"skipWorldBehindMenu",c.skipWorldBehindMenu); c.muteAudioOnPause=bool(s,"muteAudioOnPause",c.muteAudioOnPause);
                    c.overlayEnabled=bool(s,"overlayEnabled",c.overlayEnabled); c.overlayShowCountdown=bool(s,"overlayShowCountdown",c.overlayShowCountdown); c.overlayCorner=integer(s,"overlayCorner",c.overlayCorner);
                    c.overlayAlpha=integer(s,"overlayAlpha",c.overlayAlpha); c.overlayScale=integer(s,"overlayScale",c.overlayScale); c.overlayColor=integer(s,"overlayColor",c.overlayColor);
                    c.unfocusedMode=bool(s,"unfocusedMode",c.unfocusedMode); c.unfocusedFps=integer(s,"unfocusedFps",c.unfocusedFps); c.overlayText=string(s,"overlayText",c.overlayText);
                }
            } catch (Exception ignored) {}
            c.clamp(); return c;
        }
        public synchronized void save() { clamp(); try { if(file==null) file=FabricLoader.getInstance().getConfigDir().resolve("pause_fps_limiter.json"); Files.createDirectories(file.getParent()); Files.writeString(file,toJson()); } catch(IOException ignored) {} }
        public boolean isCategoryEnabled(ScreenCategory c){return switch(c){case PAUSE->limitPause;case SETTINGS->limitSettings;case CHAT->limitChat;case INVENTORY->limitInventory;case CONTAINER->limitContainers;case MOD->limitModScreens;case OTHER->limitOtherScreens;};}
        public int fpsFor(ScreenCategory c){return switch(c){case PAUSE->pauseFps;case SETTINGS->settingsFps;case CHAT->chatFps;case INVENTORY->inventoryFps;case CONTAINER->containerFps;case MOD->modFps;case OTHER->otherFps;};}
        public void reset(){enabled=true;limitPause=limitSettings=limitChat=limitInventory=limitContainers=limitModScreens=limitOtherScreens=true;pauseFps=settingsFps=chatFps=inventoryFps=containerFps=modFps=otherFps=20;pauseDelayMs=settingsDelayMs=chatDelayMs=inventoryDelayMs=containerDelayMs=modDelayMs=otherDelayMs=3000;unfocusedDelayMs=300;skipWorldBehindMenu=false;muteAudioOnPause=false;overlayEnabled=true;overlayShowCountdown=true;overlayCorner=1;overlayAlpha=230;overlayScale=100;overlayColor=0xFFFFFF;overlayText="Pause Saver: %fps% FPS (%mode%)";unfocusedMode=true;unfocusedFps=15;}
        private void clamp(){pauseFps=clampFps(pauseFps);settingsFps=clampFps(settingsFps);chatFps=clampFps(chatFps);inventoryFps=clampFps(inventoryFps);containerFps=clampFps(containerFps);modFps=clampFps(modFps);otherFps=clampFps(otherFps);unfocusedFps=clampFps(unfocusedFps);pauseDelayMs=clampDelay(pauseDelayMs);settingsDelayMs=clampDelay(settingsDelayMs);chatDelayMs=clampDelay(chatDelayMs);inventoryDelayMs=clampDelay(inventoryDelayMs);containerDelayMs=clampDelay(containerDelayMs);modDelayMs=clampDelay(modDelayMs);otherDelayMs=clampDelay(otherDelayMs);unfocusedDelayMs=clampDelay(unfocusedDelayMs);overlayCorner=Math.max(0,Math.min(3,overlayCorner));overlayAlpha=Math.max(1,Math.min(100,overlayAlpha));overlayScale=Math.max(50,Math.min(200,overlayScale));}
        private static int clampDelay(int v){return Math.max(0,Math.min(100000,v));}
        private String toJson(){return "{\n"+"  \"enabled\": "+enabled+",\n"+"  \"limitPause\": "+limitPause+",\n"+"  \"limitSettings\": "+limitSettings+",\n"+"  \"limitChat\": "+limitChat+",\n"+"  \"limitInventory\": "+limitInventory+",\n"+"  \"limitContainers\": "+limitContainers+",\n"+"  \"limitModScreens\": "+limitModScreens+",\n"+"  \"limitOtherScreens\": "+limitOtherScreens+",\n"+"  \"pauseFps\": "+pauseFps+",\n"+"  \"settingsFps\": "+settingsFps+",\n"+"  \"chatFps\": "+chatFps+",\n"+"  \"inventoryFps\": "+inventoryFps+",\n"+"  \"containerFps\": "+containerFps+",\n"+"  \"modFps\": "+modFps+",\n"+"  \"otherFps\": "+otherFps+",\n"+"  \"pauseDelayMs\": "+pauseDelayMs+",\n"+"  \"settingsDelayMs\": "+settingsDelayMs+",\n"+"  \"chatDelayMs\": "+chatDelayMs+",\n"+"  \"inventoryDelayMs\": "+inventoryDelayMs+",\n"+"  \"containerDelayMs\": "+containerDelayMs+",\n"+"  \"modDelayMs\": "+modDelayMs+",\n"+"  \"otherDelayMs\": "+otherDelayMs+",\n"+"  \"unfocusedDelayMs\": "+unfocusedDelayMs+",\n"+"  \"skipWorldBehindMenu\": "+skipWorldBehindMenu+",\n"+"  \"muteAudioOnPause\": "+muteAudioOnPause+",\n"+"  \"overlayEnabled\": "+overlayEnabled+",\n"+"  \"overlayShowCountdown\": "+overlayShowCountdown+",\n"+"  \"overlayCorner\": "+overlayCorner+",\n"+"  \"overlayAlpha\": "+overlayAlpha+",\n"+"  \"overlayScale\": "+overlayScale+",\n"+"  \"overlayColor\": "+overlayColor+",\n"+"  \"overlayText\": \""+overlayText.replace("\\","\\\\").replace("\"","\\\"")+"\",\n"+"  \"unfocusedMode\": "+unfocusedMode+",\n"+"  \"unfocusedFps\": "+unfocusedFps+"\n}\n";}
        private static boolean bool(String s,String k,boolean f){Matcher m=Pattern.compile("\\\""+Pattern.quote(k)+"\\\"\\s*:\\s*(true|false)").matcher(s);return m.find()?Boolean.parseBoolean(m.group(1)):f;}
        private static int integer(String s,String k,int f){Matcher m=Pattern.compile("\\\""+Pattern.quote(k)+"\\\"\\s*:\\s*(-?\\d+)").matcher(s);if(!m.find())return f;try{return Integer.parseInt(m.group(1));}catch(Exception e){return f;}}
        private static String string(String s,String k,String f){Matcher m=Pattern.compile("\\\""+Pattern.quote(k)+"\\\"\\s*:\\s*\\\"((?:\\\\.|[^\\\"])*)\\\"").matcher(s);if(!m.find())return f;return m.group(1).replace("\\\"","\"").replace("\\\\","\\");}
    }

    public static final class ConfigScreen extends Screen {
        private final Screen parent;
        private int scroll;
        private int contentHeight = 0;
        private static final int TOP = 54;
        private static final int BOTTOM = 34;
        private static final int ROW = 32;

        public ConfigScreen(Screen parent){super(Component.literal("Dart: PauseFrame"));this.parent=parent;}

        @Override protected void init(){
            clearWidgets();
            int w=Math.min(420, Math.max(260, width-32));
            int x=(width-w)/2;
            int y=TOP-scroll;
            int labelW=Math.max(125, Math.min(185, w/2-8));
            int inputW=w-labelW-8;
            int maxY=height-BOTTOM;
            int sectionGap=16;
            int cursor=y;

            cursor=section("GENERAL", cursor, x, w, maxY);
            cursor=rowToggle("Limiter enabled", cursor, x,labelW,inputW,CONFIG.enabled,v->{CONFIG.enabled=v;});
            cursor=rowToggle("Unfocused window mode", cursor,x,labelW,inputW,CONFIG.unfocusedMode,v->{CONFIG.unfocusedMode=v;});
            cursor=rowInput("Unfocused FPS",cursor,x,labelW,inputW,CONFIG.unfocusedFps,v->CONFIG.unfocusedFps=clampFps(v));
            cursor=rowInput("Unfocused delay (ms)",cursor,x,labelW,inputW,CONFIG.unfocusedDelayMs,v->CONFIG.unfocusedDelayMs=clampDelay(v));

            cursor+=sectionGap;
            cursor=section("FPS LIMITS",cursor,x,w,maxY);
            cursor=rowFps("Pause FPS",cursor,x,labelW,inputW,CONFIG.pauseFps,v->CONFIG.pauseFps=v);
            cursor=rowFps("Settings / Options FPS",cursor,x,labelW,inputW,CONFIG.settingsFps,v->CONFIG.settingsFps=v);
            cursor=rowFps("Inventory FPS",cursor,x,labelW,inputW,CONFIG.inventoryFps,v->CONFIG.inventoryFps=v);
            cursor=rowFps("Containers / Chests FPS",cursor,x,labelW,inputW,CONFIG.containerFps,v->CONFIG.containerFps=v);
            cursor=rowFps("Chat FPS",cursor,x,labelW,inputW,CONFIG.chatFps,v->CONFIG.chatFps=v);
            cursor=rowFps("Mod screens FPS",cursor,x,labelW,inputW,CONFIG.modFps,v->CONFIG.modFps=v);
            cursor=rowFps("Other screens FPS",cursor,x,labelW,inputW,CONFIG.otherFps,v->CONFIG.otherFps=v);

            cursor+=sectionGap;
            cursor=section("SCREEN SWITCHING",cursor,x,w,maxY);
            cursor=rowToggle("Limit pause screen",cursor,x,labelW,inputW,CONFIG.limitPause,v->{CONFIG.limitPause=v;});
            cursor=rowToggle("Limit settings / options",cursor,x,labelW,inputW,CONFIG.limitSettings,v->{CONFIG.limitSettings=v;});
            cursor=rowToggle("Limit inventory",cursor,x,labelW,inputW,CONFIG.limitInventory,v->{CONFIG.limitInventory=v;});
            cursor=rowToggle("Limit containers / chests",cursor,x,labelW,inputW,CONFIG.limitContainers,v->{CONFIG.limitContainers=v;});
            cursor=rowToggle("Limit chat",cursor,x,labelW,inputW,CONFIG.limitChat,v->{CONFIG.limitChat=v;});
            cursor=rowToggle("Limit mod screens",cursor,x,labelW,inputW,CONFIG.limitModScreens,v->{CONFIG.limitModScreens=v;});
            cursor=rowToggle("Limit other vanilla screens",cursor,x,labelW,inputW,CONFIG.limitOtherScreens,v->{CONFIG.limitOtherScreens=v;});

            cursor+=sectionGap;
            cursor=section("GRACE DELAYS",cursor,x,w,maxY);
            cursor=rowInput("Pause delay (ms)",cursor,x,labelW,inputW,CONFIG.pauseDelayMs,v->CONFIG.pauseDelayMs=clampDelay(v));
            cursor=rowInput("Settings delay (ms)",cursor,x,labelW,inputW,CONFIG.settingsDelayMs,v->CONFIG.settingsDelayMs=clampDelay(v));
            cursor=rowInput("Inventory delay (ms)",cursor,x,labelW,inputW,CONFIG.inventoryDelayMs,v->CONFIG.inventoryDelayMs=clampDelay(v));
            cursor=rowInput("Chest / container delay (ms)",cursor,x,labelW,inputW,CONFIG.containerDelayMs,v->CONFIG.containerDelayMs=clampDelay(v));
            cursor=rowInput("Chat delay (ms)",cursor,x,labelW,inputW,CONFIG.chatDelayMs,v->CONFIG.chatDelayMs=clampDelay(v));
            cursor=rowInput("Mod screen delay (ms)",cursor,x,labelW,inputW,CONFIG.modDelayMs,v->CONFIG.modDelayMs=clampDelay(v));
            cursor=rowInput("Other screen delay (ms)",cursor,x,labelW,inputW,CONFIG.otherDelayMs,v->CONFIG.otherDelayMs=clampDelay(v));

            cursor+=sectionGap;
            cursor=section("RESOURCE SAVING",cursor,x,w,maxY);
            cursor=rowToggle("Do not render 3D world behind menu",cursor,x,labelW,inputW,CONFIG.skipWorldBehindMenu,v->{CONFIG.skipWorldBehindMenu=v;});
            cursor=rowToggle("Mute audio while paused",cursor,x,labelW,inputW,CONFIG.muteAudioOnPause,v->{CONFIG.muteAudioOnPause=v;});

            cursor+=sectionGap;
            cursor=section("STATUS OVERLAY",cursor,x,w,maxY);
            cursor=rowToggle("Show status overlay",cursor,x,labelW,inputW,CONFIG.overlayEnabled,v->{CONFIG.overlayEnabled=v;});
            cursor=rowToggle("Show countdown during grace delay",cursor,x,labelW,inputW,CONFIG.overlayShowCountdown,v->{CONFIG.overlayShowCountdown=v;});
            cursor=rowInput("Overlay opacity (%)",cursor,x,labelW,inputW,CONFIG.overlayAlpha*100/255,v->CONFIG.overlayAlpha=Math.max(20,Math.min(255,v*255/100)));
            cursor=rowInput("Overlay size (%)",cursor,x,labelW,inputW,CONFIG.overlayScale,v->CONFIG.overlayScale=Math.max(50,Math.min(200,v)));
            cursor=rowButton("Overlay corner: "+cornerName(CONFIG.overlayCorner),cursor,x,labelW,inputW,()->{CONFIG.overlayCorner=(CONFIG.overlayCorner+1)%4;CONFIG.save();init();});
            cursor=rowButton("Overlay color: "+colorName(CONFIG.overlayColor),cursor,x,labelW,inputW,()->{CONFIG.overlayColor=nextColor(CONFIG.overlayColor);CONFIG.save();init();});
            cursor=rowText("Overlay text",cursor,x,labelW,inputW,CONFIG.overlayText,v->{CONFIG.overlayText=v;});
            cursor=rowHint("Variables: %fps%  %countdown%  %mode%",cursor,x,w);

            cursor+=sectionGap;
            cursor=section("RESET / ABOUT",cursor,x,w,maxY);
            cursor=rowButton("Reset all settings",cursor,x,labelW,inputW,()->{CONFIG.reset();CONFIG.save();scroll=0;init();});
            cursor=rowHint("Author: "+AUTHOR_TOKEN,cursor,x,w);
            contentHeight=cursor+scroll+20;
            scroll=Math.max(0,Math.min(scroll,maxScroll()));

            addBottomButtons(x,w);
        }

        private int section(String title,int y,int x,int w,int maxY){
            addLabel(title,x,y,w,true); return y+27;
        }
        private int rowToggle(String label,int y,int x,int lw,int iw,boolean value,BoolSetter setter){
            addLabel(label,x,y,lw,false); addButton(value?"ON":"OFF",x+lw+8,y,iw,()->{setter.set(!value);CONFIG.save();init();}); return y+ROW;
        }
        private int rowFps(String label,int y,int x,int lw,int iw,int value,IntSetter setter){
            addLabel(label,x,y,lw,false); addInput(x+lw+8,y,iw,String.valueOf(value),v->setter.set(clampFps(v))); return y+ROW;
        }
        private int rowInput(String label,int y,int x,int lw,int iw,int value,IntSetter setter){
            addLabel(label,x,y,lw,false); addInput(x+lw+8,y,iw,String.valueOf(value),setter); return y+ROW;
        }
        private int rowText(String label,int y,int x,int lw,int iw,String value,TextSetter setter){
            addLabel(label,x,y,lw,false); EditBox box=addTextBox(x+lw+8,y,iw,value); box.setResponder(v->{setter.set(v);CONFIG.save();}); return y+ROW;
        }
        private int rowButton(String label,int y,int x,int lw,int iw,Runnable action){
            addLabel(label,x,y,lw,false); addButton("Change",x+lw+8,y,iw,action); return y+ROW;
        }
        private int rowHint(String text,int y,int x,int w){addLabel(text,x,y,w,false);return y+ROW;}
        private boolean visible(int y){return y+20>TOP && y<height-BOTTOM;}
        private void addLabel(String text,int x,int y,int w,boolean heading){
            if(!visible(y)) return;
            Button b=Button.builder(Component.literal(text),q->{}).bounds(x,y,w,20).build(); b.active=false; Screens.getWidgets(this).add(b);
        }
        private void addButton(String text,int x,int y,int w,Runnable action){
            if(!visible(y)) return;
            Screens.getWidgets(this).add(Button.builder(Component.literal(text),b->action.run()).bounds(x,y,w,20).build());
        }
        private void addInput(int x,int y,int w,String value,IntSetter setter){
            if(!visible(y)) return;
            EditBox box=addTextBox(x,y,w,value);
            box.setResponder(v->{try{setter.set(Integer.parseInt(v));CONFIG.save();}catch(Exception ignored){}});
        }
        private EditBox addTextBox(int x,int y,int w,String value){
            EditBox box=new EditBox(Minecraft.getInstance().font,x,y,w,20,Component.literal("Value"));
            box.setValue(value);
            if(visible(y)) Screens.getWidgets(this).add(box);
            return box;
        }
        private void addBottomButtons(int x,int w){
            int y=height-30; int half=(w-6)/2;
            addButton("Reset view",x,y,half,()->{scroll=0;init();});
            addButton("Done",x+half+6,y,half,()->onClose());
        }
        private int maxScroll(){return Math.max(0,contentHeight-(height-BOTTOM));}
        @Override public boolean mouseScrolled(double mouseX,double mouseY,double scrollX,double scrollY){
            int old=scroll; scroll += (int)Math.round(-scrollY*24); scroll=Math.max(0,Math.min(scroll,maxScroll())); if(old!=scroll) init(); return true;
        }
        private static int clampFps(int v){return Math.max(MIN_FPS,Math.min(MAX_FPS,v));}
        private static int clampDelay(int v){return Math.max(0,Math.min(100000,v));}
        private static String cornerName(int v){return switch(v){case 0->"Top-left";case 1->"Top-right";case 2->"Bottom-left";default->"Bottom-right";};}
        private static String colorName(int c){return switch(c){case 0x55FF55->"Green";case 0x55FFFF->"Cyan";case 0xFFFF55->"Yellow";case 0xFFAA55->"Orange";case 0xFF5555->"Red";case 0xFF55FF->"Purple";default->"White";};}
        private static int nextColor(int c){int[] a={0xFFFFFF,0x55FFFF,0x55FF55,0xFFFF55,0xFFAA55,0xFF5555,0xFF55FF};for(int i=0;i<a.length;i++)if(a[i]==c)return a[(i+1)%a.length];return a[0];}
        @Override public void onClose(){CONFIG.save();Minecraft.getInstance().gui.setScreen(parent);}
        private interface BoolSetter{void set(boolean v);} private interface IntSetter{void set(int v);} private interface TextSetter{void set(String v);}
    }
}

