import { CurrencyPipe } from '@angular/common';
import { Component, computed, input, signal } from '@angular/core';
import { ChartDatum, PricePhase } from './api.models';
import { scrubIndex } from './chart-scrub';
import { phaseRuns } from './launch-pricing';

interface ChartPoint {
  x: number;
  y: number;
  date: string;
  price: number;
  phase: PricePhase | null;
}

/** A shaded stretch behind the line, in SVG units. */
interface Band {
  x: number;
  w: number;
  phase: PricePhase;
}

const PHASE_LABEL: Record<PricePhase, string> = {
  pre_release: 'pre-release',
  early: 'early pricing',
};

/**
 * Hand-rolled SVG line chart for one variant's price history.
 *
 * No charting library on purpose: this needs one line and two labels, and
 * a dependency here would be a decision the project doesn't need to make
 * yet. If charts get richer (multiple series, brushing, tooltips on a
 * touch device), that's the point to reconsider, not before.
 *
 * Takes a plain {date, value} series so it can draw price history and
 * collection value without knowing which it is. Points with a null value are
 * dropped rather than plotted as zero: a missing observation is not a value
 * of $0, and connecting across the gap is the better reading.
 *
 * A point may carry a launch phase (pre-release, or early pricing while a new
 * card settles). Those stretches are drawn over a shaded band, so when a
 * caller does include them they still read as launch pricing, not as trend.
 *
 * An optional `compare` series (the collection's "what you paid") is drawn as
 * a dashed line on the same scale, matched to the main series by date, and
 * named in the readout and a legend.
 */
@Component({
  selector: 'price-chart',
  imports: [CurrencyPipe],
  template: `
    @if (points().length < 2) {
      <p class="chart-empty">Not enough history to chart.</p>
    } @else {
      <figure class="chart">
        <!-- Pointer events, not mouse events, so a finger works the same as a
             cursor. touch-action lets a vertical page scroll through the chart
             while a horizontal drag scrubs it. -->
        <div
          class="plot"
          (pointermove)="onPointerMove($event)"
          (pointerdown)="onPointerMove($event)"
          (pointerleave)="hover.set(null)"
          (pointercancel)="hover.set(null)"
        >
        <svg
          [attr.viewBox]="'0 0 ' + W + ' ' + H"
          preserveAspectRatio="none"
          role="img"
          [attr.aria-label]="
            'Price history from ' + first().date + ' to ' + last().date
          "
        >
          <!-- The high of what is DRAWN, which is the all-time high only
               when the whole history is drawn. Since the caller can hand us
               a 30-day slice this is deliberately not called an ATH: the
               real one is on the variant button above the chart, and a
               dashed line claiming to be it while sitting at a one-month
               peak would be the more confident of the two lies. -->
          @for (b of bands(); track b.x) {
            <rect
              class="band"
              [class.pre]="b.phase === 'pre_release'"
              [attr.x]="b.x"
              [attr.width]="b.w"
              y="0"
              [attr.height]="H"
            />
          }
          <line
            class="peak-line"
            [attr.x1]="0"
            [attr.x2]="W"
            [attr.y1]="peakY()"
            [attr.y2]="peakY()"
          />
          <path class="area" [attr.d]="areaPath()" />
          @if (comparePath()) {
            <path class="compare-line" [attr.d]="comparePath()" />
          }
          <path class="line" [attr.d]="linePath()" />
          <circle class="dot" [attr.cx]="last().x" [attr.cy]="last().y" r="4" />
          @if (hovered(); as h) {
            <line
              class="crosshair"
              [attr.x1]="h.x"
              [attr.x2]="h.x"
              [attr.y1]="0"
              [attr.y2]="H"
            />
            <circle class="hover-dot" [attr.cx]="h.x" [attr.cy]="h.y" r="4" />
          }
        </svg>
        @if (hovered(); as h) {
          <!-- Positioned in percent rather than SVG units: the svg is stretched
               by preserveAspectRatio="none", so its internal x does not match
               screen pixels, but the FRACTION along the width does. -->
          <div
            class="readout"
            [style.left.%]="(h.x / W) * 100"
            [class.flip-left]="h.x / W > 0.7"
            [class.flip-right]="h.x / W < 0.3"
          >
            @if (hasCompare()) {
              @let paid = compareOn(h.date);
              <span class="r-price">{{ label() }} {{ h.price | currency }}</span>
              @if (paid !== null) {
                <span class="r-compare">{{ compareLabel() }} {{ paid | currency }}</span>
              }
            } @else {
              <span class="r-price">{{ h.price | currency }}</span>
            }
            <span class="r-date">{{ h.date }}</span>
            @if (h.phase) {
              <span class="r-phase">{{ phaseLabel[h.phase] }}</span>
            }
          </div>
        }
        </div>
        <figcaption>
          <span class="lo">low {{ min() | currency }}</span>
          <span class="range">{{ first().date }} → {{ last().date }}</span>
          <span class="hi">high {{ max() | currency }}</span>
        </figcaption>
        @if (hasCompare()) {
          <p class="legend">
            <span class="key"><i class="line-key"></i>{{ label() }}</span>
            <span class="key"><i class="line-key dashed"></i>{{ compareLabel() }}</span>
          </p>
        }
        @if (bands().length) {
          <p class="legend">
            @if (hasPhase('pre_release')) {
              <span class="key"><i class="swatch pre"></i>pre-release</span>
            }
            @if (hasPhase('early')) {
              <span class="key"><i class="swatch"></i>early pricing</span>
            }
          </p>
        }
      </figure>
    }
  `,
  styles: `
    .chart { margin: 0; }
    .plot {
      position: relative;
      /* Vertical scroll still passes through; horizontal drag scrubs. */
      touch-action: pan-y;
      cursor: crosshair;
    }
    svg {
      width: 100%;
      height: 160px;
      display: block;
      overflow: visible;
    }
    .line {
      fill: none;
      stroke: var(--accent);
      stroke-width: 2;
      vector-effect: non-scaling-stroke;
      stroke-linejoin: round;
    }
    .area { fill: var(--accent-fade); stroke: none; }
    .dot { fill: var(--accent); }
    .crosshair {
      stroke: var(--muted);
      stroke-width: 1;
      vector-effect: non-scaling-stroke;
      pointer-events: none;
    }
    .hover-dot {
      fill: var(--accent);
      stroke: var(--bg);
      stroke-width: 2;
      vector-effect: non-scaling-stroke;
      pointer-events: none;
    }
    .readout {
      position: absolute;
      top: -0.1rem;
      transform: translateX(-50%);
      background: var(--surface);
      border: 1px solid var(--line);
      border-radius: 6px;
      padding: 0.2rem 0.45rem;
      font-size: 0.75rem;
      line-height: 1.35;
      white-space: nowrap;
      pointer-events: none;
      font-variant-numeric: tabular-nums;
      display: flex;
      flex-direction: column;
    }
    /* Near an edge the label would hang off the chart, so it sits inboard. */
    .readout.flip-left { transform: translateX(-100%); }
    .readout.flip-right { transform: translateX(0); }
    .r-price { font-weight: 600; }
    .r-date { color: var(--muted); }
    .r-phase { color: var(--warn); }
    .r-compare { color: var(--muted); }
    .compare-line {
      fill: none;
      stroke: var(--muted);
      stroke-width: 1.5;
      stroke-dasharray: 5 4;
      vector-effect: non-scaling-stroke;
    }
    .line-key {
      width: 1.1rem;
      border-top: 2px solid var(--accent);
    }
    .line-key.dashed { border-top: 2px dashed var(--muted); }
    /* Launch pricing: a tint behind the line, darker before release. */
    .band { fill: var(--warn); opacity: 0.08; }
    .band.pre { opacity: 0.16; }
    .legend {
      display: flex;
      gap: 0.9rem;
      margin: 0.35rem 0 0;
      font-size: 0.75rem;
      color: var(--muted);
    }
    .key { display: inline-flex; align-items: center; gap: 0.3rem; }
    .swatch {
      width: 0.7rem;
      height: 0.7rem;
      border-radius: 2px;
      background: var(--warn);
      opacity: 0.25;
    }
    .swatch.pre { opacity: 0.45; }
    .peak-line {
      stroke: var(--line);
      stroke-width: 1;
      stroke-dasharray: 3 3;
      vector-effect: non-scaling-stroke;
    }
    figcaption {
      display: flex;
      justify-content: space-between;
      font-size: 0.75rem;
      color: var(--muted);
      margin-top: 0.4rem;
      font-variant-numeric: tabular-nums;
    }
    /* Lives here, not in search.css: Angular scopes styles per component,
       so a rule for this element written in the parent never applies. */
    .chart-empty {
      color: var(--muted);
      font-size: 0.85rem;
      margin: 0;
    }
  `,
})
export class PriceChart {
  readonly series = input.required<readonly ChartDatum[]>();
  /** A second line on the same scale, matched by date. */
  readonly compare = input<readonly ChartDatum[] | null>(null);
  /** Names for the two lines, shown only when there are two. */
  readonly label = input('');
  readonly compareLabel = input('');

  protected readonly W = 600;
  protected readonly H = 160;

  /** Points with no value at all are dropped, not zeroed. */
  private readonly usable = computed(() =>
    this.series().filter((p) => p.value != null),
  );

  protected readonly min = computed(() =>
    Math.min(...this.usable().map((p) => p.value!)),
  );
  protected readonly max = computed(() =>
    Math.max(...this.usable().map((p) => p.value!)),
  );

  /** The compare series by date, for the few days both series have. */
  private readonly compareByDate = computed(() => {
    const map = new Map<string, number>();
    for (const p of this.compare() ?? []) if (p.value != null) map.set(p.date, p.value);
    return map;
  });

  protected readonly hasCompare = computed(() => this.compareByDate().size > 0);

  /**
   * The vertical scale, which must fit both lines when there are two. The
   * caption's low and high stay the main series' own, because that's what
   * they are labelled as.
   */
  private readonly scale = computed(() => {
    const values = this.usable().map((p) => p.value!);
    for (const v of this.compareByDate().values()) values.push(v);
    return { lo: Math.min(...values), hi: Math.max(...values) };
  });

  private y(value: number): number {
    const { lo, hi } = this.scale();
    const pad = 8;
    // A dead-flat series would divide by zero; render it mid-height.
    return hi === lo ? this.H / 2 : this.H - pad - ((value - lo) / (hi - lo)) * (this.H - pad * 2);
  }

  protected compareOn(date: string): number | null {
    return this.compareByDate().get(date) ?? null;
  }

  protected readonly points = computed<ChartPoint[]>(() => {
    const pts = this.usable();
    if (pts.length < 2) return [];
    return pts.map((p, i) => ({
      x: (i / (pts.length - 1)) * this.W,
      y: this.y(p.value!),
      date: p.date,
      price: p.value!,
      phase: p.phase ?? null,
    }));
  });

  /** The compare line, drawn only through days the main series also has. */
  protected readonly comparePath = computed(() => {
    const byDate = this.compareByDate();
    if (!byDate.size) return '';
    const segs: string[] = [];
    for (const p of this.points()) {
      const v = byDate.get(p.date);
      if (v === undefined) continue;
      segs.push(`${segs.length ? 'L' : 'M'}${p.x.toFixed(2)},${this.y(v).toFixed(2)}`);
    }
    return segs.length > 1 ? segs.join(' ') : '';
  });

  protected readonly phaseLabel = PHASE_LABEL;

  /**
   * One shaded rect per run of launch-phase points, reaching half a step
   * either side of its first and last point so neighbouring runs meet.
   */
  protected readonly bands = computed<Band[]>(() => {
    const pts = this.points();
    if (pts.length < 2) return [];
    const half = this.W / (pts.length - 1) / 2;
    return phaseRuns(pts.map((p) => p.phase)).map((r) => {
      const x = Math.max(0, pts[r.from].x - half);
      const end = Math.min(this.W, pts[r.to].x + half);
      return { x, w: end - x, phase: r.phase };
    });
  });

  protected hasPhase(phase: PricePhase): boolean {
    return this.bands().some((b) => b.phase === phase);
  }

  /**
   * Index of the point under the pointer, or null.
   *
   * Costs nothing: every point is already in the browser to draw the line, so
   * this is arithmetic on an array in memory - no request, no query, nothing
   * fetched per hover. And because points are evenly spaced across the width
   * (x = i / (n-1) * W), the one under the cursor is a rounding rather than a
   * search, so it stays O(1) whether the chart holds 30 days or 900.
   */
  protected readonly hover = signal<number | null>(null);

  protected readonly hovered = computed<ChartPoint | null>(() => {
    const i = this.hover();
    if (i === null) return null;
    return this.points()[i] ?? null;
  });

  protected onPointerMove(event: PointerEvent): void {
    const n = this.points().length;
    if (n < 2) return;
    // Measured against the RENDERED box, not the viewBox: the svg is stretched
    // by preserveAspectRatio="none", so screen pixels and svg units differ.
    // A zero-width box yields NaN, which scrubIndex rejects.
    const rect = (event.currentTarget as HTMLElement).getBoundingClientRect();
    this.hover.set(scrubIndex((event.clientX - rect.left) / rect.width, n));
  }

  protected readonly first = computed(() => this.points()[0]);
  protected readonly last = computed(() => this.points()[this.points().length - 1]);

  /** Smallest y is the highest price, since SVG's origin is the top left. */
  protected readonly peakY = computed(() => {
    const pts = this.points();
    return pts.length ? Math.min(...pts.map((p) => p.y)) : 0;
  });

  protected readonly linePath = computed(() =>
    this.points()
      .map((p, i) => `${i === 0 ? 'M' : 'L'}${p.x.toFixed(2)},${p.y.toFixed(2)}`)
      .join(' '),
  );

  protected readonly areaPath = computed(() => {
    const pts = this.points();
    if (!pts.length) return '';
    return `${this.linePath()} L${this.W},${this.H} L0,${this.H} Z`;
  });
}
