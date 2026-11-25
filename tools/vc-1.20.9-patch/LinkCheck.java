import org.objectweb.asm.*;
import java.nio.file.*;
import java.util.*;
import java.util.zip.*;
/** java LinkCheck class-file jar-or-dir... : every field/method/class reference of the class must resolve (with supertypes). */
public class LinkCheck {
  static Map<String, byte[]> classes = new HashMap<>();
  public static void main(String[] a) throws Exception {
    for (int i = 1; i < a.length; i++) load(Path.of(a[i]));
    byte[] me = Files.readAllBytes(Path.of(a[0]));
    int[] bad = {0}, ok = {0};
    new ClassReader(me).accept(new ClassVisitor(Opcodes.ASM9) {
      public MethodVisitor visitMethod(int acc, String n, String d, String s, String[] e) {
        return new MethodVisitor(Opcodes.ASM9) {
          public void visitFieldInsn(int op, String o, String n, String d) { check(o, n, d, false); }
          public void visitMethodInsn(int op, String o, String n, String d, boolean itf) { check(o, n, d, true); }
          public void visitTypeInsn(int op, String t) { if (!t.startsWith("[") && !exists(t)) { System.out.println("MISSING class " + t); bad[0]++; } }
          void check(String o, String n, String d, boolean m) {
            if (o.startsWith("[")) return;
            if (!exists(o)) { System.out.println("MISSING class " + o); bad[0]++; return; }
            if (!classes.containsKey(o)) { ok[0]++; return; } // JDK
            if (find(o, n, d, m)) ok[0]++; else { System.out.println("MISSING " + (m ? "method " : "field ") + o + "." + n + d); bad[0]++; }
          }
        };
      }
    }, 0);
    System.out.println(ok[0] + " refs ok, " + bad[0] + " missing");
    if (bad[0] > 0) System.exit(1);
  }
  static boolean exists(String c) { if (classes.containsKey(c)) return true; try { Class.forName(c.replace('/', '.'), false, null); return true; } catch (Throwable t) { return false; } }
  static boolean find(String o, String n, String d, boolean m) {
    Deque<String> q = new ArrayDeque<>(List.of(o)); Set<String> seen = new HashSet<>();
    while (!q.isEmpty()) {
      String c = q.poll(); if (!seen.add(c)) continue;
      byte[] b = classes.get(c);
      if (b == null) { if (c.startsWith("java/")) return true; continue; }
      boolean[] hit = {false}; List<String> sup = new ArrayList<>();
      new ClassReader(b).accept(new ClassVisitor(Opcodes.ASM9) {
        public void visit(int v, int acc, String name, String sig, String s, String[] itf) { if (s != null) sup.add(s); if (itf != null) sup.addAll(List.of(itf)); }
        public FieldVisitor visitField(int acc, String fn, String fd, String s, Object v) { if (!m && fn.equals(n) && fd.equals(d)) hit[0] = true; return null; }
        public MethodVisitor visitMethod(int acc, String mn, String md, String s, String[] e) { if (m && mn.equals(n) && md.equals(d)) hit[0] = true; return null; }
      }, ClassReader.SKIP_CODE);
      if (hit[0]) return true;
      q.addAll(sup);
    }
    return false;
  }
  static void load(Path p) throws Exception {
    if (Files.isDirectory(p)) { try (var w = Files.walk(p)) { for (Path f : w.filter(x -> x.toString().endsWith(".class")).toList()) classes.put(p.relativize(f).toString().replace('\\','/').replaceAll("\\.class$", ""), Files.readAllBytes(f)); } return; }
    try (var z = new ZipInputStream(Files.newInputStream(p))) { for (ZipEntry e; (e = z.getNextEntry()) != null; ) if (e.getName().endsWith(".class")) classes.putIfAbsent(e.getName().replaceAll("\\.class$", ""), z.readAllBytes()); }
  }
}
