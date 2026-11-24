package net.visualclient.modules.misc;

import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_1132;
import net.minecraft.class_2338;
import net.minecraft.class_310;
import net.minecraft.class_642;
import net.visualclient.core.Category;
import net.visualclient.core.ClientModule;
import net.visualclient.core.setting.BoolSetting;

/**
 * Tells Visual Launcher what you are doing, for its Discord status: server address, world name, dimension and
 * biome. Nothing leaves the computer here - the module only writes visualclient/presence.json in the game folder
 * (once a second when something changed), and the launcher reads it.
 */
public final class PresenceModule extends ClientModule {
   public static final PresenceModule INSTANCE = new PresenceModule();
   private final BoolSetting shareBiome = this.add(new BoolSetting("Share biome", true));
   private final BoolSetting shareWorld = this.add(new BoolSetting("Share world name", true));
   private String last = "";
   private long lastWrite;
   private int ticks;

   private PresenceModule() {
      super("Discord Status", "Lets Visual Launcher show your server and biome in Discord. Only a small file in your game folder, nothing is sent anywhere.", Category.MISC);
      this.defaultEnabled(true);
      this.shareBiome.describe("Shows the biome you are standing in, e.g. Cherry Grove.");
      this.shareWorld.describe("Shows the name of your singleplayer world.");
   }

   @Override
   protected void tick() {
      if (++this.ticks % 20 == 0) {
         String body = this.snapshot(class_310.method_1551());
         long now = System.currentTimeMillis();
         if (!body.equals(this.last) || now - this.lastWrite > 15000L) {
            this.last = body;
            this.lastWrite = now;
            write("{\"v\":1," + body + ",\"time\":" + now + "}");
         }
      }
   }

   @Override
   protected void onDisable() {
      this.last = "";
      try {
         Files.deleteIfExists(file());
      } catch (Throwable var2) {
      }
   }

   private String snapshot(class_310 mc) {
      String state = "menu";
      String server = "";
      String serverName = "";
      String world = "";
      String dimension = "";
      String biome = "";
      if (mc.field_1687 != null && mc.field_1724 != null) {
         if (mc.method_1496()) {
            state = "singleplayer";
            if (this.shareWorld.on()) {
               try {
                  class_1132 s = mc.method_1576();
                  if (s != null) {
                     world = s.method_27728().method_150();
                  }
               } catch (Throwable var12) {
               }
            }
         } else {
            state = "multiplayer";
            class_642 sd = mc.method_1558();
            if (sd != null) {
               server = sd.field_3761 == null ? "" : sd.field_3761;
               serverName = sd.field_3752 == null ? "" : sd.field_3752;
            }
         }

         try {
            dimension = mc.field_1687.method_27983().method_29177().toString();
         } catch (Throwable var11) {
         }

         if (this.shareBiome.on()) {
            try {
               class_2338 pos = mc.field_1724.method_24515();
               biome = mc.field_1687.method_23753(pos).method_40230().map(k -> k.method_29177().toString()).orElse("");
            } catch (Throwable var10) {
            }
         }
      }

      return "\"state\":" + str(state) + ",\"server\":" + str(server) + ",\"serverName\":" + str(serverName)
         + ",\"world\":" + str(world) + ",\"dimension\":" + str(dimension) + ",\"biome\":" + str(biome);
   }

   private static Path file() {
      return FabricLoader.getInstance().getGameDir().resolve("visualclient").resolve("presence.json");
   }

   private static void write(String json) {
      try {
         Path f = file();
         Files.createDirectories(f.getParent());
         Path tmp = f.resolveSibling("presence.json.tmp");
         Files.writeString(tmp, json, StandardCharsets.UTF_8);
         try {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
         } catch (AtomicMoveNotSupportedException var3) {
            Files.move(tmp, f, StandardCopyOption.REPLACE_EXISTING);
         }
      } catch (Throwable var4) {
      }
   }

   private static String str(String s) {
      StringBuilder b = new StringBuilder(s.length() + 2).append('"');
      for (int i = 0; i < s.length(); i++) {
         char c = s.charAt(i);
         switch (c) {
            case '"' -> b.append("\\\"");
            case '\\' -> b.append("\\\\");
            case '\n' -> b.append("\\n");
            case '\r' -> b.append("\\r");
            case '\t' -> b.append("\\t");
            default -> {
               if (c < 0x20) {
                  b.append(String.format("\\u%04x", (int)c));
               } else {
                  b.append(c);
               }
            }
         }
      }
      return b.append('"').toString();
   }
}
