import { Component, signal } from '@angular/core';
import { Movers } from './movers';
import { Search } from './search';
import { Sets } from './sets';

type Tab = 'search' | 'movers' | 'sets';

/**
 * Three screens, switched by a signal rather than the router. There is still
 * no deep-linking requirement; adding @angular/router for that would be
 * ceremony. Revisit when a screen needs its own URL (sharing a link, browser
 * back/forward) - that's the point routing starts earning its keep, and with
 * a third screen it's closer than it was.
 */
@Component({
  selector: 'app-root',
  imports: [Search, Movers, Sets],
  templateUrl: './app.html',
  styleUrl: './app.css',
})
export class App {
  protected readonly tab = signal<Tab>('search');
}
