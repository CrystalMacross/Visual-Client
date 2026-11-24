package net.visualstyles.style;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.class_310;
import net.minecraft.class_3283;
import net.minecraft.class_3288;

/**
 * Writes the picked styles into the folder pack resourcepacks/Visual Styles, keeps that pack enabled on top
 * and reloads the textures. No resource pack screen involved.
 */
public final class StylePack {
   public static final String FOLDER = "Visual Styles";
   public static final String PACK_ID = "file/" + FOLDER;
   /** Resource pack format of Minecraft 1.21.11 (75.0). */
   private static final String MCMETA = "{\n  \"pack\": {\n    \"description\": \"Visual Styles - your picks from the K menu\",\n    \"min_format\": 75,\n    \"max_format\": 75\n  }\n}\n";
   private static final String README = "This pack is written by the Visual Styles mod.\n"
      + "Pick styles in game with the Styles key (default K). The mod rewrites this folder every time,\n"
      + "so put your own textures into config/visualstyles/custom/<collection>/ instead.\n";

   private StylePack() {
   }

   public static Path dir() {
      return FabricLoader.getInstance().getGameDir().resolve("resourcepacks").resolve(FOLDER);
   }

   /** Writes the pack, enables it and reloads. Returns a short message for the menu. */
   public static String apply() {
      try {
         int files = write();
         if (!enableAndReload()) {
            return "Saved, but Minecraft did not list the pack. Open the Resource Packs screen once.";
         }
         return files == 0 ? "Back to vanilla" : "Applied";
      } catch (Exception e) {
         return "Could not apply: " + e.getMessage();
      }
   }

   /** On start: rebuilds the pack if styles are picked but the folder is gone or the pack was switched off. */
   public static void checkOnStartup() {
      if (!Styles.anyActive()) {
         return;
      }
      boolean present = Files.isRegularFile(dir().resolve("pack.mcmeta"));
      if (!present || !isEnabled()) {
         apply();
      }
   }

   public static boolean isEnabled() {
      try {
         class_3283 repo = class_310.method_1551().method_1520();
         for (Object o : repo.method_14444()) {
            if (PACK_ID.equals(((class_3288)o).method_14463())) {
               return true;
            }
         }
      } catch (Exception ignored) {
      }
      return false;
   }

   /** True while a built-in dark inventory style is worn (container titles are then drawn light). */
   public static boolean darkInventory() {
      Style s = Styles.selected(Coll.INVENTORY);
      return !s.vanilla() && !s.custom() && !s.pack();
   }

   // ------------------------------------------------------------------ writing

   /** Writes the pack folder for the current picks; returns the number of textures written. */
   public static int write() throws IOException {
      Path root = dir();
      Files.createDirectories(root);
      deleteTree(root.resolve("assets"));
      Files.writeString(root.resolve("pack.mcmeta"), MCMETA);
      Files.writeString(root.resolve("README.txt"), README);
      icon().write(root.resolve("pack.png"));
      int count = 0;
      // a whole pack first, so the picks of the other collections are written over it
      Style whole = Styles.selected(Coll.PACKS);
      if (whole.id.startsWith("full:")) {
         String id = whole.id.substring("full:".length());
         Path base = root.normalize();
         for (String rel : PackStyles.fullFiles(id)) {
            byte[] b = PackStyles.fullBytes(id, rel);
            Path target = base.resolve(rel).normalize();
            if (b == null || !target.startsWith(base.resolve("assets"))) {
               continue;
            }
            Files.createDirectories(target.getParent());
            Files.write(target, b);
            count++;
         }
      }
      if (whole.id.startsWith("zipfull:")) {
         Path src = ImportedPacks.fullDir(whole.id.substring("zipfull:".length()));
         Path assets = src == null ? null : src.resolve("assets");
         if (assets != null && Files.isDirectory(assets)) {
            List<Path> all;
            try (Stream<Path> walk = Files.walk(assets)) {
               all = walk.filter(Files::isRegularFile).toList();
            }
            for (Path f : all) {
               Path target = root.resolve("assets").resolve(assets.relativize(f).toString()).normalize();
               if (!target.startsWith(root.resolve("assets").normalize())) {
                  continue;
               }
               Files.createDirectories(target.getParent());
               Files.copy(f, target, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
               count++;
            }
         }
      }
      for (Coll c : Coll.values()) {
         if (c == Coll.PACKS) {
            continue;
         }
         Style s = Styles.selected(c);
         boolean extras = c.hasExtras();
         if (s.vanilla() && !extras) {
            continue;
         }
         for (String path : c.targets) {
            Pic van = Vanilla.pic(path);
            if (van == null) {
               continue;
            }
            Pic styled = null;
            if (!s.vanilla()) {
               try {
                  styled = s.apply(path, van.copy());
               } catch (Exception ignored) {
                  styled = null;
               }
            }
            if (styled == null && !extras) {
               continue;
            }
            Pic out = c.extras(path, styled != null ? styled : van);
            Path target = root.resolve("assets").resolve("minecraft").resolve(path);
            out.write(target);
            byte[] meta = null;
            if (styled != null && s.custom()) {
               meta = s.customMeta(Styles.customFile(c, s, path));
            } else if (styled != null && s.pack()) {
               meta = s.packMeta.apply(path);
            }
            if (meta == null && (long)out.h * van.w == (long)van.h * out.w) {
               meta = Vanilla.bytes(path + ".mcmeta");
            }
            if (meta != null) {
               Files.write(target.resolveSibling(target.getFileName().toString() + ".mcmeta"), meta);
            }
            count++;
         }
      }
      return count;
   }

   private static void deleteTree(Path dir) throws IOException {
      if (!Files.exists(dir)) {
         return;
      }
      List<Path> all = new ArrayList<>();
      try (Stream<Path> walk = Files.walk(dir)) {
         walk.forEach(all::add);
      }
      all.sort(Comparator.reverseOrder());
      for (Path p : all) {
         Files.deleteIfExists(p);
      }
   }

   /** pack.png: violet to cyan with a small white crystal. */
   private static Pic icon() {
      int n = 64;
      Pic p = new Pic(n, n);
      for (int y = 0; y < n; y++) {
         for (int x = 0; x < n; x++) {
            float t = (x + y) / (2.0F * (n - 1));
            p.set(x, y, Col.lerp(0xFF8B5CF6, 0xFF38BDF8, t));
         }
      }
      int c = n / 2;
      for (int y = 0; y < n; y++) {
         for (int x = 0; x < n; x++) {
            int d = Math.abs(x - c) + Math.abs(y - c);
            if (d <= 18) {
               int inner = d <= 8 ? 0xFFFFFFFF : 0xCCFFFFFF;
               p.set(x, y, Col.over(inner, p.get(x, y)));
            }
         }
      }
      return p;
   }

   // ------------------------------------------------------------------ enabling

   static boolean enableAndReload() {
      class_310 mc = class_310.method_1551();
      class_3283 repo = mc.method_1520();
      repo.method_14445();
      boolean found = false;
      for (Object o : repo.method_14441()) {
         if (PACK_ID.equals(((class_3288)o).method_14463())) {
            found = true;
            break;
         }
      }
      if (!found) {
         return false;
      }
      List<String> ids = new ArrayList<>();
      for (Object o : repo.method_14444()) {
         String id = ((class_3288)o).method_14463();
         if (!PACK_ID.equals(id)) {
            ids.add(id);
         }
      }
      ids.add(PACK_ID);
      repo.method_14447(ids);
      // Options.updateResourcePacks: stores the list in options.txt (so the pack stays on after a restart)
      mc.field_1690.method_49598(repo);
      // reload the textures (if updateResourcePacks already started a reload, this returns that one)
      mc.method_1521();
      return true;
   }
}
