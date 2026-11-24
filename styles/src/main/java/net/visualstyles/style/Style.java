package net.visualstyles.style;

import java.nio.file.Files;
import java.nio.file.Path;

/** One look for a collection. Built-in styles transform the vanilla texture; custom styles load PNGs. */
public final class Style {
   public interface Op {
      /** Returns the new texture for this path, or null to keep vanilla for it. */
      Pic apply(String path, Pic vanilla) throws Exception;
   }

   public static final Style VANILLA = new Style("vanilla", "Vanilla", 0xFF8A8F9E, null, null);

   public final String id;
   public final String name;
   public final int swatch;
   private final Op op;
   /** File or folder in config/visualstyles/custom/... for custom styles, else null. */
   public final Path source;

   public Style(String id, String name, int swatch, Op op) {
      this(id, name, swatch, op, null);
   }

   /** Animation metadata of bundled pack textures (path -> .mcmeta bytes or null), else null. */
   public final java.util.function.Function<String, byte[]> packMeta;

   public Style(String id, String name, int swatch, Op op, Path source) {
      this(id, name, swatch, op, source, null);
   }

   public Style(String id, String name, int swatch, Op op, Path source, java.util.function.Function<String, byte[]> packMeta) {
      this.id = id;
      this.name = name;
      this.swatch = swatch;
      this.op = op;
      this.source = source;
      this.packMeta = packMeta;
   }

   public boolean pack() {
      return this.packMeta != null;
   }

   public boolean vanilla() {
      return this.op == null;
   }

   /** A pack the player imported as .zip (config/visualstyles/imported/...). */
   public boolean imported() {
      return this.id.startsWith("zip:") || this.id.startsWith("zipfull:");
   }

   public boolean custom() {
      return this.source != null;
   }

   public Pic apply(String path, Pic vanilla) throws Exception {
      return this.op == null ? vanilla : this.op.apply(path, vanilla);
   }

   /** Animation metadata next to a custom texture (name.png.mcmeta), or null. */
   public byte[] customMeta(Path texture) {
      if (texture == null) {
         return null;
      }
      try {
         Path meta = texture.resolveSibling(texture.getFileName().toString() + ".mcmeta");
         return Files.isRegularFile(meta) ? Files.readAllBytes(meta) : null;
      } catch (Exception e) {
         return null;
      }
   }
}
