# merge Mojang (ProGuard) + Fabric intermediary (tiny v2) into tiny v2: official intermediary named
import sys, re
moj, inter, out = sys.argv[1:4]
PRIM = {'void':'V','boolean':'Z','byte':'B','char':'C','short':'S','int':'I','long':'J','float':'F','double':'D'}
cls_n2o = {}; classes = []  # (named, obf, members)
cur = None
for line in open(moj, encoding='utf8'):
    if line.startswith('#') or not line.strip(): continue
    if not line.startswith(' '):
        n, o = line.rstrip(':\n').split(' -> ')
        cur = [n.replace('.','/'), o.replace('.','/'), []]; classes.append(cur); cls_n2o[cur[0]] = cur[1]
    else:
        cur[2].append(line.strip())
def desc(t):
    a = 0
    while t.endswith('[]'): t = t[:-2]; a += 1
    d = PRIM.get(t) or 'L' + cls_n2o.get(t.replace('.','/'), t.replace('.','/')) + ';'
    return '[' * a + d
# intermediary
ic = {}; im = {}; ifd = {}
cc = None
for line in open(inter, encoding='utf8'):
    p = line.rstrip('\n').split('\t')
    if p[0] == 'c': cc = p[1]; ic[p[1]] = p[2]
    elif len(p) > 2 and p[1] == 'm': im[(cc, p[3], p[2])] = p[4]
    elif len(p) > 2 and p[1] == 'f': ifd[(cc, p[3], p[2])] = p[4]
o = open(out, 'w', encoding='utf8')
o.write('tiny\t2\t0\tofficial\tintermediary\tnamed\n')
for n, ob, mem in classes:
    o.write(f'c\t{ob}\t{ic.get(ob, ob)}\t{n}\n')
    for m in mem:
        lhs, obn = m.split(' -> ')
        lhs = re.sub(r'^\d+:\d+:', '', lhs)
        lhs = re.sub(r':\d+:\d+$', '', lhs)
        if '(' in lhs:
            t, rest = lhs.split(' ', 1)
            name, args = rest.split('(', 1)
            args = args.rstrip(')')
            d = '(' + ''.join(desc(x) for x in args.split(',') if x) + ')' + desc(t)
            iv = im.get((ob, obn, d))
            if iv is None: continue
            o.write(f'\tm\t{d}\t{obn}\t{iv}\t{name}\n')
        else:
            t, name = lhs.split(' ')
            d = desc(t)
            iv = ifd.get((ob, obn, d))
            if iv is None: continue
            o.write(f'\tf\t{d}\t{obn}\t{iv}\t{name}\n')
o.close()
