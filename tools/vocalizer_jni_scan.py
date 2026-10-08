#!/usr/bin/env python3
"""Extract the JNI contract consumed by a native TTS library (AArch64).

How it works
------------
* Disassemble ``.text`` with capstone (``skipdata`` so embedded data does
  not truncate the pass) with operand detail enabled.
* A register that is loaded with ``ldr D, [base, #off]`` where ``off`` is a
  JNINativeInterface slot is treated as holding that JNI entry point, until
  it is overwritten or a call clobbers the caller-saved set.  A ``blr`` of
  such a register is a candidate JNI site.
* Backward-slice argument registers through MOV/ADRP/ADD/ADR to recover the
  C string constants they hold.  Each argument is a *slot* that follows the
  register it currently occupies, so ``mov x3, x21`` retargets the slot at
  ``x21``.
* Class/method results (FindClass -> x0, GetMethodID -> x0) propagate
  forward so lookups and Call* sites can be labelled.
* Sites whose recovered ``name``/``sig`` are not plausible C strings are
  dropped: that is what separates JNI lookups from C++ vtable calls that
  merely happen to use an offset inside the table's byte range.

Usage:  python vocalizer_jni_scan.py <lib.so> [out.json]
"""
import json
import re
import sys
from elftools.elf.elffile import ELFFile
from capstone import CS_ARCH_ARM64, CS_MODE_ARM, Cs

JNI_H = (r"C:\Users\pc\AppData\Local\Android\Sdk\ndk\28.2.13676358"
         r"\toolchains\llvm\prebuilt\windows-x86_64\sysroot\usr\include"
         r"\jni.h")

UNK = "?"
RX = re.compile(r"^(x\d+|w\d+)$")
M_IDENT = re.compile(r"^[A-Za-z_$][A-Za-z0-9_$]*$")
M_SIG = re.compile(r"^(L[\w/]+;|\[[BCDFIJSVZ]|[BCDFIJSVZ]|\(.*\).+)$")
CALLER_SAVED = {"x%d" % i for i in range(18)}


def norm(reg):
    if reg and reg[0] == "w" and reg[1:].isdigit():
        return "x" + reg[1:]
    return reg


def load_jni_table(path=JNI_H):
    src = open(path, encoding="utf-8", errors="replace").read()
    m = re.search(r"struct\s+JNINativeInterface\s*\{(.*?)\n\};", src, re.S)
    body = re.sub(r"/\*.*?\*/", "", m.group(1), flags=re.S)
    body = re.sub(r"//.*?$", "", body, flags=re.M)
    names = []
    for it in (x.strip() for x in body.split(";") if x.strip()):
        mm = re.search(r"\(\s*\*\s*([A-Za-z_]\w*)\s*\)", it)
        names.append(mm.group(1) if mm else None)
    return {i * 8: n for i, n in enumerate(names) if n}


def main(path, out_path=None):
    table = load_jni_table()
    elf = ELFFile(open(path, "rb"))
    text_sec = elf.get_section_by_name(".text")
    ro_sec = elf.get_section_by_name(".rodata")
    text, rodata, ro_addr = (text_sec.data(), ro_sec.data(),
                             ro_sec["sh_addr"])

    def cstr(addr):
        if addr is None:
            return None
        o = addr - ro_addr
        if not (0 <= o < len(rodata)):
            return None
        e = rodata.find(b"\x00", o)
        if e < 0:
            return None
        try:
            s = rodata[o:e].decode("utf-8")
        except UnicodeDecodeError:
            return None
        return s if s and s.isprintable() else None

    md = Cs(CS_ARCH_ARM64, CS_MODE_ARM)
    md.detail = True
    md.skipdata = True
    insns = list(md.disasm(text, text_sec["sh_addr"]))

    def writes(ins):
        # regs_access() -> (regs_read, regs_write); index 1 is the writes.
        try:
            return {norm(ins.reg_name(r))
                    for r in ins.regs_access()[1]
                    if RX.match(ins.reg_name(r) or "")}
        except Exception:
            return set()

    # ---- backward slice ------------------------------------------------
    def slice_args(call_i, arg_regs):
        slots = {r: r for r in arg_regs}
        out, pending = {}, {}

        for j in range(call_i - 1, max(-1, call_i - 240), -1):
            if len(out) == len(arg_regs):
                break
            ins = insns[j]
            if ins.mnemonic in ("bl", "blr"):
                if "x0" in slots.values():
                    for a in arg_regs:
                        if a not in out and norm(slots[a]) == "x0":
                            out[a] = None
                continue
            mm = re.match(r"^(x\d+|w\d+),\s*(.*)$", ins.op_str)
            if not mm:
                continue
            dst, rest = norm(mm.group(1)), mm.group(2)
            targets = [a for a in arg_regs
                       if a not in out and norm(slots[a]) == dst]
            if not targets:
                continue

            if ins.mnemonic == "adrp":
                m2 = re.match(r"#(0x[0-9a-fA-F]+)", rest)
                val = (int(m2.group(1), 16) + pending.pop(dst, 0)
                       if m2 else None)
                out.update({a: val for a in targets})
            elif ins.mnemonic == "adr":
                m2 = re.match(r"#(0x[0-9a-fA-F]+)", rest)
                val = int(m2.group(1), 16) if m2 else None
                out.update({a: val for a in targets})
            elif ins.mnemonic == "add":
                m2 = re.match(r"(x\d+|sp),\s*#(0x[0-9a-fA-F]+|-?\d+)$", rest)
                if m2 and norm(m2.group(1)) == dst and dst[0] != "w":
                    pending[dst] = pending.get(dst, 0) + int(m2.group(2), 0)
                else:
                    out.update({a: None for a in targets})
            elif ins.mnemonic == "mov":
                m2 = re.match(r"(x\d+|w\d+|xzr|wzr|sp)$", rest)
                if m2 and norm(m2.group(1)) != dst:
                    for a in targets:
                        slots[a] = m2.group(1)
                else:
                    out.update({a: None for a in targets})
            else:
                out.update({a: None for a in targets})
        return out

    # ---- forward pass: sites + class/method dataflow --------------------
    findings = {"findclass": [], "fieldid": [], "methodid": [],
                "setfield": [], "getfield": [], "call": [], "newobject": [],
                "other": []}
    rejected = []

    fnptr = {}
    clsreg, midreg = {}, {}
    x0_cls, pending_mid = None, None

    for i, ins in enumerate(insns):
        mn, ops = ins.mnemonic, ins.op_str

        if mn == "stp" and "x29" in ops and "x30" in ops:
            fnptr, clsreg, midreg = {}, {}, {}
            x0_cls, pending_mid = None, None
            continue

        w = writes(ins)
        pre_ptr, pre_cls, pre_mid = dict(fnptr), dict(clsreg), dict(midreg)

        for r in w:
            fnptr.pop(r, None)
            clsreg.pop(r, None)
            midreg.pop(r, None)
        if "x0" in w:
            x0_cls, pending_mid = None, None

        if mn == "blr":
            callee = norm(ops.strip())
            jname = fnptr.get(callee)
            addr = hex(ins.address)

            if jname is None:
                pass
            elif jname == "FindClass":
                cname = cstr(slice_args(i, ["x1"]).get("x1"))
                clsreg["x0"] = cname or UNK
                x0_cls = cname or UNK
                findings["findclass"].append({"addr": addr, "class": cname})

            elif jname in ("GetFieldID", "GetStaticFieldID"):
                v = slice_args(i, ["x2", "x3"])
                nm, sg = cstr(v.get("x2")), cstr(v.get("x3"))
                if _ok(nm, sg):
                    findings["fieldid"].append({
                        "addr": addr, "kind": jname,
                        "class": clsreg.get("x1"), "name": nm, "sig": sg})
                else:
                    rejected.append({"addr": addr, "op": jname,
                                     "name": nm, "sig": sg})

            elif jname in ("GetMethodID", "GetStaticMethodID"):
                v = slice_args(i, ["x2", "x3"])
                nm, sg = cstr(v.get("x2")), cstr(v.get("x3"))
                if _ok(nm, sg):
                    findings["methodid"].append({
                        "addr": addr, "kind": jname,
                        "class": clsreg.get("x1"), "name": nm, "sig": sg})
                    pending_mid = (clsreg.get("x1") or UNK, nm, sg)
                    midreg["x0"] = pending_mid
                else:
                    rejected.append({"addr": addr, "op": jname,
                                     "name": nm, "sig": sg})

            elif re.match(r"^Set\w+Field$", jname):
                findings["setfield"].append(
                    {"addr": addr, "op": jname, "class": clsreg.get("x1")})
            elif re.match(r"^Get\w+Field$", jname):
                findings["getfield"].append(
                    {"addr": addr, "op": jname, "class": clsreg.get("x1")})
            elif jname.startswith("Call"):
                mid = "x1" if "Static" in jname else "x2"
                findings["call"].append(
                    {"addr": addr, "op": jname, "class": clsreg.get(mid),
                     "method": midreg.get(mid)})
            elif jname.startswith("NewObject"):
                findings["newobject"].append(
                    {"addr": addr, "class": clsreg.get("x1"),
                     "method": midreg.get("x2")})
            else:
                findings["other"].append(
                    {"addr": addr, "op": jname, "class": clsreg.get("x1")})

            for r in list(fnptr):
                if r in CALLER_SAVED:
                    fnptr.pop(r, None)
            if "x0" in w:
                pending_mid = None

        elif mn == "bl":
            for r in list(fnptr):
                if r in CALLER_SAVED:
                    fnptr.pop(r, None)

        if mn == "mov":
            m = re.match(r"^(x\d+|w\d+),\s*(x\d+|w\d+)$", ops)
            if m:
                d, s = norm(m.group(1)), norm(m.group(2))
                if s in pre_cls:
                    clsreg[d] = pre_cls[s]
                elif s == "x0" and x0_cls is not None:
                    clsreg[d] = x0_cls
                if s in pre_mid:
                    midreg[d] = pre_mid[s]
                elif s == "x0" and pending_mid is not None:
                    midreg[d] = pending_mid
                if s in pre_ptr:
                    fnptr[d] = pre_ptr[s]
        elif mn == "ldr":
            m = re.match(
                r"^(x\d+|w\d+),\s*\[([wx]\d+),\s*#(0x[0-9a-fA-F]+|\d+)\]$",
                ops)
            if m:
                d = norm(m.group(1))
                off = int(m.group(3), 0)
                if off in table:
                    fnptr[d] = table[off]

    payload = dict(findings)
    payload["rejected"] = rejected
    if out_path:
        open(out_path, "w", encoding="utf-8").write(
            json.dumps(payload, indent=1, ensure_ascii=False))
    else:
        sys.stdout.write(json.dumps(payload, indent=1, ensure_ascii=False))


def _ok(name, sig):
    return bool(name and sig and M_IDENT.match(name) and M_SIG.match(sig))


if __name__ == "__main__":
    main(sys.argv[1], sys.argv[2] if len(sys.argv) > 2 else None)
