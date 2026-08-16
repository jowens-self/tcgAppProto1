import { Component, signal } from '@angular/core';
import { Movers } from './movers';
import { Search } from './search';

type Tab = 'search' | 'movers';

/**
 * Two screens, switched by a signal rather than the router. There are two of
 * them and no deep-linking requirement yet; adding @angular/router for that
 * would be ceremony. Revisit when a screen needs its own URL (sharing a
 * link, browser back/forward) - that's the point routing starts earning its
 * keep.
 */
@Component({
  selector: 'app-root',
  imports: [Search, Movers],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly tab = signal<Tab>('search');
}
