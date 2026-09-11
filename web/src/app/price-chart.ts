import { CurrencyPipe } from '@angular/common';
import { Component, computed, input, signal } from '@angular/core';
import { ChartDatum } from './api.models';
import { scrubIndex } from './chart-scrub';

interface ChartPoint {
  x: number;
  y: number;
  date: string;
  price: number;
}

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
          <line
            class="peak-line"
            [attr.x1]="0"
            [attr.x2]="W"
            [attr.y1]="peakY()"
            [attr.y2]="peakY()"
          />
          <path class="area" [attr.d]="areaPath()" />
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
            <span class="r-price">{{ h.price | currency }}</span>
            <span class="r-date">{{ h.date }}</span>
          </div>
        }
        </div>
        <figcaption>
          <span class="lo">low {{ min() | currency }}</span>
          <span class="range">{{ first().date }} → {{ last().date }}</span>
          <span class="hi">high {{ max() | currency }}</span>
        </figcaption>
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
  readonly series = input.required<ChartDatum[]>();

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

  protected readonly points = computed<ChartPoint[]>(() => {
    const pts = this.usable();
    if (pts.length < 2) return [];
    const lo = this.min();
    const hi = this.max();
    // A dead-flat series would divide by zero; render it mid-height.
    const span = hi - lo || 1;
    const pad = 8;
    return pts.map((p, i) => ({
      x: (i / (pts.length - 1)) * this.W,
      y:
        hi === lo
          ? this.H / 2
          : this.H - pad - ((p.value! - lo) / span) * (this.H - pad * 2),
      date: p.date,
      price: p.value!,
    }));
  });

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
