package net.visualstyles.style;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Styles that come from texture packs bundled in the jar (assets/visualstyles/packs/<id>/...).
 * index.txt lists "id|Pack name|textures/..." per texture; every pack becomes one style in each
 * collection it has textures for, named after the pack.
 */
public final class PackStyles {
   private static final String ROOT = "assets/visualstyles/packs/";
   private static Map<Coll, List<Style>> byColl;

   private PackStyles() {
   }

   public static List<Style> of(Coll c) {
      if (byColl == null) {
         load();
      }
      return byColl.getOrDefault(c, List.of());
   }

   private static void load() {
      Map<String, String> names = new LinkedHashMap<>();
      Map<String, Set<String>> files = new LinkedHashMap<>();
      try (InputStream in = open(ROOT + "index.txt")) {
         if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
               String[] p = line.split("\\|", 3);
               if (p.length == 3) {
                  names.putIfAbsent(p[0], p[1]);
                  files.computeIfAbsent(p[0], k -> new LinkedHashSet<>()).add(p[2]);
               }
            }
         }
      } catch (Exception ignored) {
      }
      Map<Coll, List<Style>> out = new EnumMap<>(Coll.class);
      for (Coll c : Coll.values()) {
         List<Style> list = new ArrayList<>();
         for (Map.Entry<String, Set<String>> e : files.entrySet()) {
            String id = e.getKey();
            Set<String> has = e.getValue();
            boolean any = false;
            for (String t : c.targets) {
               if (has.contains(t)) {
                  any = true;
                  break;
               }
            }
            if (!any) {
               continue;
            }
            list.add(new Style("pack:" + id, names.get(id), 0xFFFFC83D, (path, van) -> {
               if (!has.contains(path)) {
                  return null;
               }
               try (InputStream in = open(ROOT + id + "/" + path)) {
                  return in == null ? null : Pic.read(in);
               }
            }, null, path -> bytes(ROOT + id + "/" + path + ".mcmeta")));
         }
         out.put(c, List.copyOf(list));
      }
      // whole packs: full.txt lists "id|Pack name|assets/..." for every file of the pack
      Map<String, String> fullNames = new LinkedHashMap<>();
      Map<String, List<String>> full = new LinkedHashMap<>();
      try (InputStream in = open(ROOT + "full.txt")) {
         if (in != null) {
            BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = r.readLine()) != null) {
               String[] p = line.split("\\|", 3);
               if (p.length == 3 && !p[2].contains("..")) {
                  fullNames.putIfAbsent(p[0], p[1]);
                  full.computeIfAbsent(p[0], k -> new ArrayList<>()).add(p[2]);
               }
            }
         }
      } catch (Exception ignored) {
      }
      List<Style> whole = new ArrayList<>();
      for (String id : full.keySet()) {
         whole.add(new Style("full:" + id, fullNames.get(id), 0xFF38BDF8, (path, van) -> {
            if (!"pack.png".equals(path)) {
               return null;
            }
            try (InputStream in = open(ROOT + id + "/full/pack.png")) {
               return in == null ? null : Pic.read(in);
            }
         }, null, path -> null));
      }
      out.put(Coll.PACKS, List.copyOf(whole));
      fullFiles = full;
      byColl = out;
   }

   private static Map<String, List<String>> fullFiles = Map.of();

   /** Every file of a whole bundled pack (paths start with assets/). */
   public static List<String> fullFiles(String id) {
      if (byColl == null) {
         load();
      }
      return fullFiles.getOrDefault(id, List.of());
   }

   public static byte[] fullBytes(String id, String rel) {
      return bytes(ROOT + id + "/full/" + rel);
   }

   private static InputStream open(String resource) {
      return PackStyles.class.getClassLoader().getResourceAsStream(resource);
   }

   private static byte[] bytes(String resource) {
      try (InputStream in = open(resource)) {
         return in == null ? null : in.readAllBytes();
      } catch (Exception e) {
         return null;
      }
   }
}
