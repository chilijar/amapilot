#!/usr/bin/env python3
"""DHU 截图几何探针（无 PIL 环境的兜底，2026-11 实战验证）。

vision subagent 不可用时（模型切换/路径 glitch），用纯 Python（zlib+struct）
解码 DHU `screenshot` 输出的 PNG，做亮度分析：

  scripts/dhu-shot-inspect.py <png>                     # ASCII 全图粗览
  scripts/dhu-shot-inspect.py <png> --region X Y W H    # 区域放大（5px/字符）
  scripts/dhu-shot-inspect.py <png> --clusters X0 X1 Y0 Y1
                                                       # 带内亮字簇中心（按键/行定位）

已知用途：车机键盘按键定位（QWERTY pitch 70px）、车速环/竖排几何核对、
结果列表行定位。精度 ≈5-10px，足够 tap 注入；文字内容不可读（无 OCR），
读文本仍需 vision subagent 或换装 PIL。
"""
import struct
import sys
import zlib


def decode_png(path):
    data = open(path, 'rb').read()
    assert data[:8] == b'\x89PNG\r\n\x1a\n', 'not a PNG'
    pos, w, h, bd, ct, idat = 8, 0, 0, 0, 0, b''
    while pos < len(data):
        ln, typ = struct.unpack('>I4s', data[pos:pos + 8])
        pos += 8
        chunk = data[pos:pos + ln]
        pos += ln + 4
        if typ == b'IHDR':
            w, h, bd, ct = struct.unpack('>IIBB', chunk[:10])
        elif typ == b'IDAT':
            idat += chunk
        elif typ == b'IEND':
            break
    raw = zlib.decompress(idat)
    ch = {0: 1, 2: 3, 4: 2, 6: 4}[ct]
    bpp = max(1, ch * bd // 8)
    stride = w * bpp
    out = bytearray(h * stride)
    prev = bytearray(stride)
    p = 0
    for y in range(h):
        f = raw[p]
        p += 1
        line = bytearray(raw[p:p + stride])
        p += stride
        if f == 1:
            for i in range(bpp, stride):
                line[i] = (line[i] + line[i - bpp]) & 255
        elif f == 2:
            for i in range(stride):
                line[i] = (line[i] + prev[i]) & 255
        elif f == 3:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                line[i] = (line[i] + ((a + prev[i]) >> 1)) & 255
        elif f == 4:
            for i in range(stride):
                a = line[i - bpp] if i >= bpp else 0
                b = prev[i]
                c = prev[i - bpp] if i >= bpp else 0
                pa, pb, pc = abs(b - c), abs(a - c), abs(a + b - 2 * c)
                pr = a if (pa <= pb and pa <= pc) else (b if pb <= pc else c)
                line[i] = (line[i] + pr) & 255
        out[y * stride:(y + 1) * stride] = line
        prev = line
    return w, h, ch, bytes(out), stride


def main():
    if len(sys.argv) < 2:
        print(__doc__)
        sys.exit(1)
    path = sys.argv[1]
    w, h, ch, px, stride = decode_png(path)
    print(f'decoded {w}x{h} channels={ch}')

    def lum(x, y):
        o = y * stride + x * ch
        if ch >= 3:
            return (px[o] * 299 + px[o + 1] * 587 + px[o + 2] * 114) // 1000
        return px[o]

    chars = ' .:-=+*#%@'
    args = sys.argv[2:]
    if args and args[0] == '--clusters':
        x0, x1, y0, y1 = map(int, args[1:5])
        for band in range(y0, y1, 10):
            cols = [max(lum(x, y) for y in range(band, min(band + 10, h), 2))
                    for x in range(x0, x1)]
            clusters, start = [], None
            for i, v in enumerate(cols):
                if v > 90 and start is None:
                    start = i
                elif v <= 90 and start is not None:
                    if i - start >= 6:
                        clusters.append(x0 + (start + i) // 2)
                    start = None
            if clusters:
                print(f'y {band}-{band + 10}: clusters {clusters}')
        return
    if args and args[0] == '--region':
        x, y, rw, rh = map(int, args[1:5])
        for yy in range(y, min(y + rh, h), 8):
            line = ''
            for xx in range(x, min(x + rw, w), 5):
                v = max(lum(a, b)
                        for a in range(xx, min(xx + 5, w), 2)
                        for b in range(yy, min(yy + 8, h), 2))
                line += chars[min(9, v * 10 // 256)]
            print(f'{yy:3d} {line}')
        return
    for y in range(0, h, 12):
        line = ''
        for x in range(0, w, 10):
            v = max(lum(a, b)
                    for a in range(x, min(x + 10, w), 3)
                    for b in range(y, min(y + 12, h), 3))
            line += chars[min(9, v * 10 // 256)]
        print(f'{y:3d} {line}')


if __name__ == '__main__':
    main()
