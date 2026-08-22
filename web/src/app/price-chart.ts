import { CurrencyPipe } from '@angular/common';
import { Component, computed, input } from '@angular/core';
import { PricePoint } from './api.models';

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
 * touch device), that's the point to reconsider - not before.
 *
 * Charts `trendablePrice`, which the server already resolved via the
 * documented market->mid->low fallback. Days where even that is null are
 * dropped rather than plotted as zero - a missing observation is not a
 * price of $0, and connecting across the gap is the honest reading.
 */
@Component({
  selector: 'price-chart',
  imports: [CurrencyPipe],
  template: `
    @if (points().length < 2) {
      <p class="chart-empty">Not enough history to chart.</p>
    } @else {
      <figure class="chart">
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
        </svg>
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
  readonly history = input.required<PricePoint[]>();

  protected readonly W = 600;
  protected readonly H = 160;

  /** Days with no usable price at all are dropped, not zeroed. */
  private readonly usable = computed(() =>
    this.history().filter((p) => p.trendablePrice != null),
  );

  protected readonly min = computed(() =>
    Math.min(...this.usable().map((p) => p.trendablePrice!)),
  );
  protected readonly max = computed(() =>
    Math.max(...this.usable().map((p) => p.trendablePrice!)),
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
          : this.H - pad - ((p.trendablePrice! - lo) / span) * (this.H - pad * 2),
      date: p.snapshotDate,
      price: p.trendablePrice!,
    }));
  });

  protected readonly first = computed(() => this.points()[0]);
  protected readonly last = computed(() => this.points()[this.points().length - 1]);

  /** Smallest y is the highest price - SVG's origin is the top left. */
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
