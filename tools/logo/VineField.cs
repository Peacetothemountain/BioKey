using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Imaging;
using System.Runtime.InteropServices;

// Builds the slither field for the BioKey logo's vines.
//
// Each vine's centreline is traced; for every pixel on or near a vine we take its nearest
// centreline point: the vine's sideways direction n there, and how far along the vine it is (s).
// The shader moves the vine sideways by n * A * sin(k s - w t): a wave travelling along every
// vine, like a snake, with each cross-section moving as one piece.
// Stored as U = n sin(k s) * env and V = n cos(k s) * env, so the shader needs no trig per
// texel: d = A (U cos wt - V sin wt). env anchors the vines where they wrap round the letters.
public static class VineField
{
    public static float Wavelength = 280f;  // art px along the vine
    public static float PinNear = 4f, PinFar = 52f; // centreline distance from letters: no motion .. full motion
    const int Reach = 30;                   // how far outside a vine the field extends (art px)
    const float Close = 6f;                 // gap between ridges to close (art px)
    const int SpurTrim = 18;                // centreline spurs shorter than this are trimmed (art px)
    const int MinVine = 2500;               // smaller pieces are specks, not vines

    public static int W, H;
    public static float[] Ux, Uy, Vx, Vy;
    public static byte[] TentA, LetterA;
    public static bool[] Skel;

    static readonly int[] ox = { 1, -1, 0, 0, 1, 1, -1, -1 }, oy = { 0, 0, 1, -1, 1, -1, 1, -1 };
    static readonly float[] ow = { 1, 1, 1, 1, 1.4142f, 1.4142f, 1.4142f, 1.4142f };

    static byte[] Alpha(Bitmap b)
    {
        var d = b.LockBits(new Rectangle(0, 0, b.Width, b.Height), ImageLockMode.ReadOnly, PixelFormat.Format32bppArgb);
        var raw = new byte[d.Stride * b.Height];
        Marshal.Copy(d.Scan0, raw, 0, raw.Length);
        b.UnlockBits(d);
        var a = new byte[b.Width * b.Height];
        for (int y = 0; y < b.Height; y++) for (int x = 0; x < b.Width; x++) a[y * b.Width + x] = raw[y * d.Stride + x * 4 + 3];
        return a;
    }

    static float Smooth(float e0, float e1, float v) { float t = Math.Max(0, Math.Min(1, (v - e0) / (e1 - e0))); return t * t * (3 - 2 * t); }

    // Chamfer distance (approx. Euclidean) from all source pixels, and the nearest source index.
    static void Distance(bool[] src, out float[] dist, out int[] near)
    {
        int n = W * H;
        dist = new float[n]; near = new int[n];
        for (int i = 0; i < n; i++) { dist[i] = src[i] ? 0 : 1e9f; near[i] = src[i] ? i : -1; }
        int[] dx = { -1, 0, 1, -1 }, dy = { -1, -1, -1, 0 };
        float[] w = { 1.4142f, 1, 1.4142f, 1 };
        for (int pass = 0; pass < 2; pass++)
            for (int k = 0; k < n; k++)
            {
                int i = pass == 0 ? k : n - 1 - k;
                int x = i % W, y = i / W;
                for (int j = 0; j < 4; j++)
                {
                    int nx = pass == 0 ? x + dx[j] : x - dx[j], ny = pass == 0 ? y + dy[j] : y - dy[j];
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int q = ny * W + nx;
                    if (dist[q] + w[j] < dist[i]) { dist[i] = dist[q] + w[j]; near[i] = near[q]; }
                }
            }
    }

    static float[] Blur(float[] f, float sigma)
    {
        int r = (int)Math.Ceiling(sigma * 3);
        var k = new float[2 * r + 1]; float sum = 0;
        for (int i = -r; i <= r; i++) { k[i + r] = (float)Math.Exp(-i * i / (2 * sigma * sigma)); sum += k[i + r]; }
        for (int i = 0; i < k.Length; i++) k[i] /= sum;
        var t = new float[W * H]; var o = new float[W * H];
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
        { float s = 0; for (int i = -r; i <= r; i++) { int xx = Math.Max(0, Math.Min(W - 1, x + i)); s += f[y * W + xx] * k[i + r]; } t[y * W + x] = s; }
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
        { float s = 0; for (int i = -r; i <= r; i++) { int yy = Math.Max(0, Math.Min(H - 1, y + i)); s += t[yy * W + x] * k[i + r]; } o[y * W + x] = s; }
        return o;
    }

    static List<List<int>> Components(bool[] mask)
    {
        var all = new List<List<int>>();
        var seen = new bool[W * H];
        for (int start = 0; start < W * H; start++)
        {
            if (!mask[start] || seen[start]) continue;
            var members = new List<int>();
            var stack = new Stack<int>(); stack.Push(start); seen[start] = true;
            while (stack.Count > 0)
            {
                int p = stack.Pop(); members.Add(p);
                int x = p % W, y = p / W;
                for (int j = 0; j < 8; j++)
                {
                    int nx = x + ox[j], ny = y + oy[j];
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int q = ny * W + nx;
                    if (mask[q] && !seen[q]) { seen[q] = true; stack.Push(q); }
                }
            }
            all.Add(members);
        }
        return all;
    }

    public static void Build(string tentaclesPng, string lettersPng)
    {
        using (var t = new Bitmap(tentaclesPng)) using (var l = new Bitmap(lettersPng))
        { W = t.Width; H = t.Height; TentA = Alpha(t); LetterA = Alpha(l); }
        int n = W * H;
        var vine = new bool[n];
        var letter = new bool[n];
        for (int i = 0; i < n; i++) { vine[i] = TentA[i] > 60; letter[i] = LetterA[i] > 96; }

        // The vines are drawn as ridges with gaps between them: close them into solid bodies,
        // then drop specks (stray edges of letters), so each vine traces as one piece.
        float[] dd; int[] nn;
        Distance(vine, out dd, out nn);
        var outside = new bool[n];
        for (int i = 0; i < n; i++) outside[i] = dd[i] > Close;
        Distance(outside, out dd, out nn);
        for (int i = 0; i < n; i++) vine[i] = dd[i] > Close;
        foreach (var c in Components(vine)) if (c.Count < MinVine) foreach (int p in c) vine[p] = false;
        var holes = new bool[n];
        for (int i = 0; i < n; i++) holes[i] = !vine[i];
        foreach (var c in Components(holes)) if (c.Count < 900) foreach (int p in c) vine[p] = true;

        // Each vine's centreline (Zhang-Suen thinning), with short spurs trimmed off.
        var skel = (bool[])vine.Clone();
        Func<int, int, bool> on = (x, y) => x >= 0 && y >= 0 && x < W && y < H && skel[y * W + x];
        var kill = new List<int>();
        bool changed = true;
        while (changed)
        {
            changed = false;
            for (int step = 0; step < 2; step++)
            {
                kill.Clear();
                for (int y = 1; y < H - 1; y++) for (int x = 1; x < W - 1; x++)
                {
                    if (!skel[y * W + x]) continue;
                    bool p2 = on(x, y - 1), p3 = on(x + 1, y - 1), p4 = on(x + 1, y), p5 = on(x + 1, y + 1),
                         p6 = on(x, y + 1), p7 = on(x - 1, y + 1), p8 = on(x - 1, y), p9 = on(x - 1, y - 1);
                    bool[] ring = { p2, p3, p4, p5, p6, p7, p8, p9, p2 };
                    int b = 0, a = 0;
                    for (int j = 0; j < 8; j++) { if (ring[j]) b++; if (!ring[j] && ring[j + 1]) a++; }
                    if (b < 2 || b > 6 || a != 1) continue;
                    if (step == 0 ? (p2 && p4 && p6) || (p4 && p6 && p8) : (p2 && p4 && p8) || (p2 && p6 && p8)) continue;
                    kill.Add(y * W + x);
                }
                foreach (int i in kill) skel[i] = false;
                if (kill.Count > 0) changed = true;
            }
        }
        for (int it = 0; it < SpurTrim; it++)
        {
            kill.Clear();
            for (int i = 0; i < n; i++)
            {
                if (!skel[i]) continue;
                int x = i % W, y = i / W, deg = 0;
                for (int j = 0; j < 8; j++) if (on(x + ox[j], y + oy[j])) deg++;
                if (deg <= 1) kill.Add(i);
            }
            foreach (int i in kill) skel[i] = false;
        }

        // Distance along each centreline from its leftmost point (Dijkstra on the centreline).
        var s = new float[n];
        var comp = new int[n];
        for (int i = 0; i < n; i++) { s[i] = float.MaxValue; comp[i] = -1; }
        int ncomp = 0;
        foreach (var members in Components(skel))
        {
            foreach (int p in members) comp[p] = ncomp;
            int seed = members[0];
            foreach (int p in members) if (p % W < seed % W) seed = p;
            var pq = new SortedSet<Tuple<float, int>>(Comparer<Tuple<float, int>>.Create((a, b) => a.Item1 != b.Item1 ? a.Item1.CompareTo(b.Item1) : a.Item2.CompareTo(b.Item2)));
            s[seed] = 0; pq.Add(Tuple.Create(0f, seed));
            while (pq.Count > 0)
            {
                var top = pq.Min; pq.Remove(top);
                int p = top.Item2, x = p % W, y = p / W;
                for (int j = 0; j < 8; j++)
                {
                    int nx = x + ox[j], ny = y + oy[j];
                    if (nx < 0 || ny < 0 || nx >= W || ny >= H) continue;
                    int q = ny * W + nx;
                    if (!skel[q]) continue;
                    float ns = s[p] + ow[j];
                    if (ns < s[q]) { if (s[q] != float.MaxValue) pq.Remove(Tuple.Create(s[q], q)); s[q] = ns; pq.Add(Tuple.Create(ns, q)); }
                }
            }
            ncomp++;
        }

        // Direction along the centreline: least-squares slope of s over nearby centreline points.
        // Where it's unclear (junctions, where two fronts meet in a loop) its strength drops.
        var gx = new float[n]; var gy = new float[n];
        const int R = 10;
        for (int i = 0; i < n; i++)
        {
            if (!skel[i]) continue;
            int x = i % W, y = i / W;
            float sxx = 0, sx = 0, sy = 0; int cnt = 0;
            for (int yy = Math.Max(0, y - R); yy <= Math.Min(H - 1, y + R); yy++)
                for (int xx = Math.Max(0, x - R); xx <= Math.Min(W - 1, x + R); xx++)
                {
                    int q = yy * W + xx;
                    if (!skel[q] || comp[q] != comp[i] || q == i) continue;
                    float dx = xx - x, dy = yy - y, d2 = dx * dx + dy * dy;
                    if (d2 > R * R) continue;
                    float ds = s[q] - s[i];
                    if (Math.Abs(ds) > 2.2f * R) continue; // another stretch of the same vine
                    sx += ds * dx; sy += ds * dy; sxx += d2; cnt++;
                }
            if (cnt < 4) continue;
            gx[i] = 2 * sx / sxx; gy[i] = 2 * sy / sxx;
        }

        float[] dVine, dSkel, dLetter; int[] nearVine, nearSkel, nearLetter;
        Distance(vine, out dVine, out nearVine);
        Distance(skel, out dSkel, out nearSkel);
        Distance(letter, out dLetter, out nearLetter);

        float k = (float)(2 * Math.PI / Wavelength);
        Ux = new float[n]; Uy = new float[n]; Vx = new float[n]; Vy = new float[n];
        for (int i = 0; i < n; i++)
        {
            if (dVine[i] > Reach || nearSkel[i] < 0) continue;
            int src = nearSkel[i];
            float tx = gx[src], ty = gy[src];
            float mag = (float)Math.Sqrt(tx * tx + ty * ty);
            if (mag < 1e-3f) continue;
            tx /= mag; ty /= mag;
            float nx = -ty, ny = tx;
            float env = Smooth(0.45f, 0.85f, mag)
                * Smooth(PinNear, PinFar, dLetter[src])
                * (1 - Smooth(Reach - 12, Reach, dVine[i]));
            float sn = (float)Math.Sin(k * s[src]) * env, cs = (float)Math.Cos(k * s[src]) * env;
            Ux[i] = nx * sn; Uy[i] = ny * sn; Vx[i] = nx * cs; Vy[i] = ny * cs;
        }
        Ux = Blur(Ux, 5); Uy = Blur(Uy, 5); Vx = Blur(Vx, 5); Vy = Blur(Vy, 5);
        Skel = skel;
    }

    // Field image: two halves side by side, U (left) and V (right), x in red, y in green, 0 = 128.
    public static void Save(string path, int scale)
    {
        int w = W / scale, h = H / scale;
        using (var b = new Bitmap(w * 2, h, PixelFormat.Format24bppRgb))
        {
            for (int y = 0; y < h; y++) for (int x = 0; x < w; x++)
            {
                float ux = 0, uy = 0, vx = 0, vy = 0;
                for (int j = 0; j < scale; j++) for (int i = 0; i < scale; i++)
                { int p = (y * scale + j) * W + x * scale + i; ux += Ux[p]; uy += Uy[p]; vx += Vx[p]; vy += Vy[p]; }
                float c = scale * scale;
                b.SetPixel(x, y, Color.FromArgb(Enc(ux / c), Enc(uy / c), 128));
                b.SetPixel(w + x, y, Color.FromArgb(Enc(vx / c), Enc(vy / c), 128));
            }
            b.Save(path, ImageFormat.Png);
        }
    }

    // Debug: vine bodies in grey, motion strength in orange, centrelines in white.
    public static void Debug(string path)
    {
        using (var b = new Bitmap(W, H, PixelFormat.Format24bppRgb))
        {
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
            {
                int i = y * W + x;
                float m = (float)Math.Sqrt(Ux[i] * Ux[i] + Uy[i] * Uy[i] + Vx[i] * Vx[i] + Vy[i] * Vy[i]);
                int g = TentA[i] / 3 + LetterA[i] / 6;
                var c = Skel[i] ? Color.White : Color.FromArgb(Math.Min(255, g + (int)(m * 255)), Math.Min(255, g + (int)(m * 120)), g);
                b.SetPixel(x, y, c);
            }
            b.Save(path, ImageFormat.Png);
        }
    }

    static int Enc(float v) { return Math.Max(0, Math.Min(255, (int)Math.Round(128 + v * 127))); }
    static float Dec(float v) { return (v - 128) / 127f; }

    // Preview frame, sampling the saved field (bilinear) exactly as the device will: dark ground,
    // letters in primary, displaced vines in tertiary.
    public static void Frame(string fieldPng, string outPng, float t, float amp, int outW)
    {
        using (var f = new Bitmap(fieldPng))
        {
            int fw = f.Width / 2, fh = f.Height;
            var fr = new float[f.Width * fh]; var fg = new float[f.Width * fh];
            for (int y = 0; y < fh; y++) for (int x = 0; x < f.Width; x++) { var c = f.GetPixel(x, y); fr[y * f.Width + x] = c.R; fg[y * f.Width + x] = c.G; }
            Func<float[], float, float, float> bil = (a, x, y) =>
            {
                x = Math.Max(0, Math.Min(fw - 1.001f, x)); y = Math.Max(0, Math.Min(fh - 1.001f, y));
                int x0 = (int)x, y0 = (int)y; float u = x - x0, v = y - y0;
                return (a[y0 * f.Width + x0] * (1 - u) + a[y0 * f.Width + x0 + 1] * u) * (1 - v) + (a[(y0 + 1) * f.Width + x0] * (1 - u) + a[(y0 + 1) * f.Width + x0 + 1] * u) * v;
            };
            float scale = (float)outW / W; int outH = (int)(H * scale) / 2 * 2;
            float cw = (float)Math.Cos(2 * Math.PI * t), sw = (float)Math.Sin(2 * Math.PI * t);
            using (var o = new Bitmap(outW, outH, PixelFormat.Format24bppRgb))
            {
                Color ground = Color.FromArgb(18, 19, 24), prim = Color.FromArgb(168, 199, 250), tert = Color.FromArgb(215, 186, 255);
                for (int y = 0; y < outH; y++) for (int x = 0; x < outW; x++)
                {
                    float ax = x / scale, ay = y / scale; // art coords
                    float fx = ax / W * fw - 0.5f, fy = ay / H * fh - 0.5f;
                    float ux = Dec(bil(fr, fx, fy)), uy = Dec(bil(fg, fx, fy));
                    float vx = Dec(bil(fr, fx + fw, fy)), vy = Dec(bil(fg, fx + fw, fy));
                    float dx = amp * (ux * cw - vx * sw), dy = amp * (uy * cw - vy * sw);
                    float la = Sample(LetterA, ax, ay) / 255f;
                    float ta = Sample(TentA, ax - dx, ay - dy) / 255f;
                    float r = ground.R, g = ground.G, bl = ground.B;
                    r = r * (1 - la) + prim.R * la; g = g * (1 - la) + prim.G * la; bl = bl * (1 - la) + prim.B * la;
                    r = r * (1 - ta) + tert.R * ta; g = g * (1 - ta) + tert.G * ta; bl = bl * (1 - ta) + tert.B * ta;
                    o.SetPixel(x, y, Color.FromArgb((int)r, (int)g, (int)bl));
                }
                o.Save(outPng, ImageFormat.Png);
            }
        }
    }

    static float Sample(byte[] a, float x, float y)
    {
        int x0 = (int)Math.Floor(x), y0 = (int)Math.Floor(y);
        float fx = x - x0, fy = y - y0;
        Func<int, int, float> at = (xx, yy) => (xx < 0 || yy < 0 || xx >= W || yy >= H) ? 0 : a[yy * W + xx];
        return (at(x0, y0) * (1 - fx) + at(x0 + 1, y0) * fx) * (1 - fy) + (at(x0, y0 + 1) * (1 - fx) + at(x0 + 1, y0 + 1) * fx) * fy;
    }
}
