package net.visualstyles;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.fabricmc.loader.api.FabricLoader;

/** Everything the player picked, stored in config/visualstyles.json. */
public final class Config {
   private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
   private static Config instance;

   /** collection id -> style id (missing = vanilla) */
   public Map<String, String> selected = new LinkedHashMap<>();
   /** "collection/style" */
   public List<String> favorites = new ArrayList<>();
   /** three saved loadouts */
   public List<Map<String, String>> presets = new ArrayList<>();

   /** 1 = vanilla speed, 0 = frozen, up to 3 */
   public float crystalSpin = 1.0F;
   public boolean crystalCoreOn = false;
   public String crystalCore = "33E1FF";
   public boolean anchorGlowOn = false;
   public String anchorGlow = "8B5CF6";
   /** 0 = vanilla, 1 = the fire overlay is moved far down */
   public float lowFire = 0.0F;
   /** light container titles while a dark inventory style is worn */
   public boolean darkLabels = true;
   /** menu design: studio, glass, neon, showcase, dock */
   public String ui = "studio";

   public static Config get() {
      if (instance == null) {
         load();
      }
      return instance;
   }

   public static Path file() {
      return FabricLoader.getInstance().getConfigDir().resolve("visualstyles.json");
   }

   public static void load() {
      Config c = null;
      Path f = file();
      if (Files.isRegularFile(f)) {
         try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
            c = GSON.fromJson(r, Config.class);
         } catch (Exception ignored) {
            c = null;
         }
      }
      instance = c == null ? new Config() : c;
      instance.normalize();
   }

   private void normalize() {
      if (this.selected == null) {
         this.selected = new LinkedHashMap<>();
      }
      if (this.favorites == null) {
         this.favorites = new ArrayList<>();
      }
      if (this.presets == null) {
         this.presets = new ArrayList<>();
      }
      while (this.presets.size() < 3) {
         this.presets.add(null);
      }
      if (this.crystalCore == null) {
         this.crystalCore = "33E1FF";
      }
      if (this.anchorGlow == null) {
         this.anchorGlow = "8B5CF6";
      }
      // 1.7.0: mace, wind charge and pearl became one category "mace_wind_pearl"
      migrate(this.selected);
      for (Map<String, String> p : this.presets) {
         if (p != null) {
            migrate(p);
         }
      }
      List<String> favs = new ArrayList<>();
      for (String f : this.favorites) {
         for (String old : OLD_ITEMS) {
            if (f.startsWith(old + "/")) {
               f = MERGED + f.substring(old.length());
               break;
            }
         }
         if (!favs.contains(f)) {
            favs.add(f);
         }
      }
      this.favorites = favs;
      this.crystalSpin = Math.max(0.0F, Math.min(3.0F, this.crystalSpin));
      this.lowFire = Math.max(0.0F, Math.min(1.0F, this.lowFire));
   }

   private static final String MERGED = "mace_wind_pearl";
   private static final String[] OLD_ITEMS = {"mace", "wind_charge", "pearl"};

   private static void migrate(Map<String, String> picks) {
      for (String old : OLD_ITEMS) {
         String v = picks.remove(old);
         if (v != null) {
            picks.putIfAbsent(MERGED, v);
         }
      }
   }

   public void save() {
      try {
         Path f = file();
         Files.createDirectories(f.getParent());
         try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
            GSON.toJson(this, w);
         }
      } catch (Exception ignored) {
      }
   }
}
