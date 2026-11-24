package net.visualstyles.style;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;
import net.fabricmc.loader.api.FabricLoader;
import net.visualstyles.Config;

/** Built-in styles for every collection, custom styles from the config folder, and the current picks. */
public final class Styles {
   private record Tint(String id, String name, int color, Ops.Tone tone) {
   }

   private static final Tint AQUA = new Tint("aqua", "Aqua", 0xFF33E1FF, Ops.Tone.NORMAL);
   private static final Tint BLUEBERRY = new Tint("blueberry", "Blueberry", 0xFF4F6BFF, Ops.Tone.NORMAL);
   private static final Tint LAVENDER = new Tint("lavender", "Lavender", 0xFFB79CFF, Ops.Tone.NORMAL);
   private static final Tint VIOLET = new Tint("violet", "Violet", 0xFF8B5CF6, Ops.Tone.NORMAL);
   private static final Tint SAKURA = new Tint("sakura", "Sakura", 0xFFFF8FCB, Ops.Tone.NORMAL);
   private static final Tint CRIMSON = new Tint("crimson", "Crimson", 0xFFFF3B4E, Ops.Tone.NORMAL);
   private static final Tint GOLD = new Tint("gold", "Gold", 0xFFFFC83D, Ops.Tone.NORMAL);
   private static final Tint MINT = new Tint("mint", "Mint", 0xFF6FFFC0, Ops.Tone.NORMAL);
   private static final Tint EMERALD = new Tint("emerald", "Emerald", 0xFF2ECC71, Ops.Tone.NORMAL);
   private static final Tint ICE = new Tint("ice", "Ice", 0xFFA8E6FF, Ops.Tone.LIGHT);
   private static final Tint SNOW = new Tint("snow", "Snow", 0xFFF2F6FF, Ops.Tone.LIGHT);
   private static final Tint MIDNIGHT = new Tint("midnight", "Midnight", 0xFF2B3A9E, Ops.Tone.DARK);
   private static final Tint OBSIDIAN = new Tint("obsidian", "Obsidian", 0xFF5A2D82, Ops.Tone.DARK);
   private static final Tint GLOWSTONE = new Tint("glowstone", "Glowstone", 0xFFFFB347, Ops.Tone.NORMAL);

   private static final Map<Coll, List<Style>> BUILT = new EnumMap<>(Coll.class);
   private static final Map<Coll, List<Style>> CUSTOM = new EnumMap<>(Coll.class);

   private Styles() {
   }

   // ------------------------------------------------------------------ lists

   public static List<Style> all(Coll c) {
      List<Style> built = BUILT.computeIfAbsent(c, Styles::build);
      List<Style> out = new ArrayList<>();
      out.add(built.get(0));                 // Vanilla first
      out.addAll(PackStyles.of(c));          // then the bundled texture packs
      out.addAll(ImportedPacks.of(c));       // then the packs the player imported as .zip
      out.addAll(built.subList(1, built.size()));
      out.addAll(CUSTOM.computeIfAbsent(c, Styles::scan));
      return out;
   }

   /** Reads the custom folders again (after the player added files). */
   public static void refreshCustom() {
      CUSTOM.clear();
      ImportedPacks.refresh();
   }

   public static Style find(Coll c, String id) {
      if (id != null) {
         for (Style s : all(c)) {
            if (s.id.equals(id)) {
               return s;
            }
         }
      }
      return Style.VANILLA;
   }

   public static Style selected(Coll c) {
      return find(c, Config.get().selected.get(c.id));
   }

   public static void select(Coll c, Style s) {
      Config cfg = Config.get();
      if (s == null || s.vanilla()) {
         cfg.selected.remove(c.id);
      } else {
         cfg.selected.put(c.id, s.id);
      }
      cfg.save();
   }

   /** True if anything should be in the generated pack. */
   public static boolean anyActive() {
      for (Coll c : Coll.values()) {
         if (!selected(c).vanilla() || c.hasExtras()) {
            return true;
         }
      }
      return false;
   }

   public static boolean favorite(Coll c, Style s) {
      return Config.get().favorites.contains(c.id + "/" + s.id);
   }

   public static void toggleFavorite(Coll c, Style s) {
      Config cfg = Config.get();
      String key = c.id + "/" + s.id;
      if (!cfg.favorites.remove(key)) {
         cfg.favorites.add(key);
      }
      cfg.save();
   }

   /** The texture as it will look with this style and the collection's extras (never null if vanilla has it). */
   public static Pic render(Coll c, Style s, String path) {
      if (c == Coll.PACKS) {
         try {
            return s.vanilla() ? null : s.apply(path, null);
         } catch (Exception e) {
            return null;
         }
      }
      Pic van = Vanilla.pic(path);
      if (van == null) {
         return null;
      }
      Pic out = null;
      if (!s.vanilla()) {
         try {
            out = s.apply(path, van.copy());
         } catch (Exception ignored) {
            out = null;
         }
      }
      return c.extras(path, out == null ? van : out);
   }

   // ------------------------------------------------------------------ custom styles

   public static Path customRoot() {
      return FabricLoader.getInstance().getConfigDir().resolve("visualstyles").resolve("custom");
   }

   public static Path customDir(Coll c) {
      return customRoot().resolve(c.id);
   }

   private static List<Style> scan(Coll c) {
      List<Style> out = new ArrayList<>();
      scan(c, customDir(c), out);
      if (c == Coll.MACE_WIND_PEARL) {
         // folders of the separate categories before 1.7.0
         for (String old : new String[]{"mace", "wind_charge", "pearl"}) {
            scan(c, customRoot().resolve(old), out);
         }
      }
      return out;
   }

   private static void scan(Coll c, Path dir, List<Style> out) {
      if (!Files.isDirectory(dir)) {
         return;
      }
      try (Stream<Path> files = Files.list(dir)) {
         for (Path p : files.sorted().toList()) {
            String name = p.getFileName().toString();
            if (Files.isDirectory(p)) {
               out.add(new Style("custom:" + name + "/", name, 0xFF38BDF8, (path, van) -> {
                  Path f = findIn(p, path);
                  return f == null ? null : Pic.read(f);
               }, p));
            } else if (name.toLowerCase(Locale.ROOT).endsWith(".png")) {
               String shown = name.substring(0, name.length() - 4);
               List<String> targets = singleTargets(c, name);
               out.add(new Style("custom:" + name, shown, 0xFF38BDF8, (path, van) -> targets.contains(path) ? Pic.read(p) : null, p));
            }
         }
      } catch (IOException ignored) {
      }
   }

   /**
    * Textures a single custom PNG replaces: if its name is one of the collection's textures (mace.png in
    * "Mace + Wind + Pearl") only that one, else all of the collection's main textures.
    */
   static List<String> singleTargets(Coll c, String fileName) {
      String n = fileName.toLowerCase(Locale.ROOT);
      for (String t : c.customTargets) {
         if (t.endsWith("/" + n)) {
            return List.of(t);
         }
      }
      return c.customTargets;
   }

   /**
    * Finds the file for a vanilla path inside a custom folder: the full path after textures/
    * (entity/equipment/humanoid/netherite.png), the last folder + name (humanoid_leggings/netherite.png),
    * or just the file name (netherite_sword.png).
    */
   static Path findIn(Path dir, String path) {
      String rel = path.startsWith("textures/") ? path.substring("textures/".length()) : path;
      String[] parts = rel.split("/");
      String name = parts[parts.length - 1];
      String parentAndName = parts.length >= 2 ? parts[parts.length - 2] + "/" + name : name;
      for (String candidate : new String[]{rel, parentAndName, name}) {
         Path f = dir.resolve(candidate);
         if (Files.isRegularFile(f)) {
            return f;
         }
      }
      return null;
   }

   /** The custom file a custom style uses for this path (for its .mcmeta), or null. */
   public static Path customFile(Coll c, Style s, String path) {
      if (s.source == null) {
         return null;
      }
      if (Files.isDirectory(s.source)) {
         return findIn(s.source, path);
      }
      return singleTargets(c, s.source.getFileName().toString()).contains(path) ? s.source : null;
   }

   // ------------------------------------------------------------------ built-in styles

   private static List<Style> build(Coll c) {
      List<Style> l = new ArrayList<>();
      l.add(Style.VANILLA);
      switch (c) {
         case CRYSTAL -> {
            l.add(new Style("see_through", "See-through", 0xFFCFD3E0, (p, s) -> Ops.crystalSeeThrough(s)));
            l.add(new Style("core_only", "Core only", 0xFFFF7AD9, (p, s) -> Ops.crystalCoreOnly(s)));
            l.add(new Style("invisible", "Invisible", 0xFF3A3F50, (p, s) -> Ops.invisible(s)));
            tints(l, Ops.NONE, null, AQUA, BLUEBERRY, LAVENDER, VIOLET, SAKURA, CRIMSON, GOLD, MINT, SNOW, MIDNIGHT, GLOWSTONE);
            sunset(l, Ops.NONE, Ops.Tone.NORMAL);
            rainbow(l, Ops.NONE, Ops.Tone.NORMAL);
         }
         case ANCHOR -> {
            tints(l, Ops.GLOW, null, AQUA, BLUEBERRY, VIOLET, SAKURA, CRIMSON, GOLD, MINT, SNOW, MIDNIGHT);
            // Glowstone: warm yellow-orange body and charge lights, like the glowstone block
            l.add(new Style("glowstone", "Glowstone", 0xFFFFB347,
               (p, s) -> Ops.recolorGlow(Ops.paint(s, Ops.solid(0xFFFFB347), Ops.Tone.NORMAL, Ops.GLOW), 0xFFFFB347)));
            rainbow(l, Ops.GLOW, Ops.Tone.NORMAL);
         }
         case GLOWSTONE -> {
            tints(l, Ops.NONE, null, AQUA, BLUEBERRY, LAVENDER, VIOLET, SAKURA, CRIMSON, MINT, EMERALD, SNOW, MIDNIGHT);
            rainbow(l, Ops.NONE, Ops.Tone.NORMAL);
            l.add(new Style("smooth", "Smooth", 0xFFFFD27A, (p, s) -> Ops.smooth(s, 0.7F)));
            l.add(new Style("flat", "Flat", 0xFFE8B85C, (p, s) -> Ops.flat(s, 0.72F)));
            l.add(new Style("dim", "Dim", 0xFF8A6A2E, (p, s) -> Ops.multiply(s, 0.6F)));
         }
         case OBSIDIAN -> {
            tints(l, Ops.NONE, null, AQUA, BLUEBERRY, LAVENDER, VIOLET, SAKURA, CRIMSON, GOLD, MINT, EMERALD, SNOW, MIDNIGHT);
            rainbow(l, Ops.NONE, Ops.Tone.NORMAL);
            l.add(new Style("smooth", "Smooth", 0xFF2A1F3D, (p, s) -> Ops.smooth(s, 0.7F)));
            l.add(new Style("flat", "Flat", 0xFF1E1730, (p, s) -> Ops.flat(s, 0.72F)));
            l.add(new Style("dark", "Dark", 0xFF0B0812, (p, s) -> Ops.multiply(s, 0.55F)));
            l.add(new Style("light", "Light", 0xFF6B5A8E, (p, s) -> Ops.toward(s, 0xFFFFFFFF, 0.35F)));
            l.add(new Style("mono", "Mono", 0xFF4A4A4A, (p, s) -> Ops.gray(s)));
         }
         case ARMOR -> {
            tints(l, Ops.NONE, null, SAKURA, ICE, AQUA, BLUEBERRY, LAVENDER, VIOLET, CRIMSON, GOLD, EMERALD, MINT, SNOW, MIDNIGHT, OBSIDIAN);
            rainbow(l, Ops.NONE, Ops.Tone.NORMAL);
         }
         case SWORD, PICKAXE, AXE -> {
            tints(l, Ops.WOOD, null, AQUA, BLUEBERRY, LAVENDER, VIOLET, SAKURA, CRIMSON, GOLD, EMERALD, MINT, SNOW, MIDNIGHT);
            sunset(l, Ops.WOOD, Ops.Tone.NORMAL);
            rainbow(l, Ops.WOOD, Ops.Tone.NORMAL);
         }
         case TOTEM -> {
            tints(l, Ops.NONE, null, AQUA, BLUEBERRY, VIOLET, SAKURA, CRIMSON, GOLD, MINT, SNOW, MIDNIGHT, GLOWSTONE);
            rainbow(l, Ops.NONE, Ops.Tone.NORMAL);
         }
         case MACE_WIND_PEARL -> {
            tints(l, Ops.NONE, null, AQUA, BLUEBERRY, LAVENDER, VIOLET, SAKURA, CRIMSON, GOLD, MINT, EMERALD, SNOW, MIDNIGHT);
            sunset(l, Ops.NONE, Ops.Tone.NORMAL);
            rainbow(l, Ops.NONE, Ops.Tone.NORMAL);
         }
         case FIRE -> {
            tints(l, Ops.NONE, Ops.Tone.FIRE, AQUA, BLUEBERRY, VIOLET, SAKURA, CRIMSON, MINT, SNOW, MIDNIGHT, GLOWSTONE);
            rainbow(l, Ops.NONE, Ops.Tone.FIRE);
         }
         case STONE, DEEPSLATE, SNOW -> {
            l.add(new Style("smooth", "Smooth", 0xFFB8BDC9, (p, s) -> Ops.smooth(s, 0.7F)));
            l.add(new Style("flat", "Flat", 0xFF9AA0AE, (p, s) -> Ops.flat(s, 0.72F)));
            l.add(new Style("dark", "Dark", 0xFF3A3F50, (p, s) -> Ops.multiply(s, 0.55F)));
            l.add(new Style("light", "Light", 0xFFE6E9F0, (p, s) -> Ops.toward(s, 0xFFFFFFFF, 0.35F)));
            l.add(new Style("cool", "Cool", 0xFF8FA7C9, (p, s) -> Ops.tintMix(s, 0xFF7FA0D6, 0.45F)));
            l.add(new Style("warm", "Warm", 0xFFC9A58F, (p, s) -> Ops.tintMix(s, 0xFFD6A27F, 0.45F)));
            l.add(new Style("violet", "Violet", 0xFF8B5CF6, (p, s) -> Ops.tintMix(s, 0xFF8B5CF6, 0.4F)));
            l.add(new Style("mono", "Mono", 0xFFA0A0A0, (p, s) -> Ops.gray(s)));
         }
         case INVENTORY -> {
            l.add(new Style("dark", "Dark", 0xFF2A2C33, (p, s) -> Ops.darkGui(s, 0, 0.0F)));
            l.add(new Style("midnight", "Midnight", 0xFF2B3A9E, (p, s) -> Ops.darkGui(s, 0xFF2B3A9E, 0.55F)));
            l.add(new Style("violet", "Violet", 0xFF8B5CF6, (p, s) -> Ops.darkGui(s, 0xFF8B5CF6, 0.45F)));
            l.add(new Style("ocean", "Ocean", 0xFF0EA5C6, (p, s) -> Ops.darkGui(s, 0xFF0EA5C6, 0.45F)));
            l.add(new Style("crimson", "Crimson", 0xFFE11D48, (p, s) -> Ops.darkGui(s, 0xFFE11D48, 0.4F)));
            l.add(new Style("forest", "Forest", 0xFF22C55E, (p, s) -> Ops.darkGui(s, 0xFF22C55E, 0.4F)));
         }
         case GLINT -> {
            tints(l, Ops.NONE, Ops.Tone.KEEP, AQUA, BLUEBERRY, VIOLET, SAKURA, CRIMSON, GOLD, MINT, SNOW);
            rainbow(l, Ops.NONE, Ops.Tone.KEEP);
            l.add(new Style("subtle", "Subtle", 0xFF6B5A8E, (p, s) -> Ops.multiply(s, 0.42F)));
            l.add(new Style("off", "Off", 0xFF2A2C33, (p, s) -> Ops.multiply(s, 0.0F)));
         }
         case CROSSHAIR -> {
            String[][] shapes = {{"dot", "Dot"}, {"tiny", "Tiny"}, {"small", "Small"}, {"gap", "Gap"}, {"circle", "Circle"}, {"square", "Square"}, {"x", "X"}};
            for (String[] sh : shapes) {
               String shape = sh[0];
               l.add(new Style(shape, sh[1], 0xFFEEF0F7, (p, s) -> Ops.crosshair(shape, s.w)));
            }
         }
      }
      return List.copyOf(l);
   }

   private static void tints(List<Style> l, Ops.Keep keep, Ops.Tone toneOverride, Tint... tints) {
      for (Tint t : tints) {
         Ops.Tone tone = toneOverride != null ? toneOverride : t.tone();
         int color = t.color();
         l.add(new Style(t.id(), t.name(), color, (p, s) -> Ops.paint(s, Ops.solid(color), tone, keep)));
      }
   }

   private static void rainbow(List<Style> l, Ops.Keep keep, Ops.Tone tone) {
      l.add(new Style("rainbow", "Rainbow", 0xFFFF4FD8, (p, s) -> Ops.paint(s, Ops.rainbow(), tone, keep)));
   }

   private static void sunset(List<Style> l, Ops.Keep keep, Ops.Tone tone) {
      l.add(new Style("sunset", "Sunset", 0xFFFF6A5C, (p, s) -> Ops.paint(s, Ops.vertical(0xFFFFB23D, 0xFFFF3D9A), tone, keep)));
   }
}
