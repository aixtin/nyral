# -*- coding: utf-8 -*-
p = "patch_gate_v3.py"
src = open(p, encoding="utf-8").read()
fixes = [
    ("信任\"\"\"\",", "信任\"''',"),
    ("信任\"\"\"\")", "信任\"''')"),
    ("$name\"\"\"\",", "$name\"''',"),
    ("$name\"\"\"\")", "$name\"''')"),
]
for old, new in fixes:
    c = src.count(old)
    print(f"anchor {old!r}: {c}")
    src = src.replace(old, new)
open(p, "w", encoding="utf-8").write(src)
print("saved")
