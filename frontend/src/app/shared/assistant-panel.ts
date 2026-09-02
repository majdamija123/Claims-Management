import { ChangeDetectionStrategy, Component, inject, input, output, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, AssistantTurn } from '../core/api.service';

/**
 * The assistant panel on a complaint.
 *
 * <p>Which unit is advising is decided by the server from the session, so the panel only
 * carries the conversation. It hides itself entirely when the assistant is not configured,
 * rather than offering a control that cannot work.
 */
@Component({
  selector: 'app-assistant-panel',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    @if (available()) {
      <div class="card assistant">
        <div class="card-head">
          <h3>Assistant</h3>
          <span class="muted small">{{ hint() }}</span>
        </div>

        <div class="thread">
          @if (turns().length === 0) {
            <div class="starters">
              <p class="muted small">Ask about this complaint, or start with:</p>
              @for (starter of starters; track starter) {
                <button class="starter" (click)="send(starter)" [disabled]="busy()">
                  {{ starter }}
                </button>
              }
            </div>
          }

          @for (turn of turns(); track $index) {
            <div class="turn" [class.mine]="turn.fromUser">
              <span class="who">{{ turn.fromUser ? 'You' : 'Assistant' }}</span>
              <div class="bubble">{{ turn.text }}</div>
              @if (!turn.fromUser) {
                <button class="link small" (click)="useAnswer.emit(turn.text)">
                  Use this as the answer
                </button>
              }
            </div>
          }

          @if (busy()) {
            <div class="turn"><span class="who">Assistant</span><div class="bubble thinking">Thinking…</div></div>
          }
          @if (error()) {
            <div class="banner error">{{ error() }}</div>
          }
        </div>

        <form class="composer" (ngSubmit)="send(draft())">
          <input
            type="text"
            name="draft"
            [ngModel]="draft()"
            (ngModelChange)="draft.set($event)"
            placeholder="Ask about this complaint…"
            [disabled]="busy()"
          />
          <button class="btn primary sm" type="submit" [disabled]="busy() || !draft().trim()">
            Ask
          </button>
        </form>
      </div>
    }
  `,
  styles: [
    `
      .assistant {
        display: flex;
        flex-direction: column;
      }
      .thread {
        padding: 14px 18px;
        display: flex;
        flex-direction: column;
        gap: 14px;
        max-height: 420px;
        overflow-y: auto;
      }
      .starters {
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 6px;
      }
      .starter {
        border: 1px solid var(--ink-300);
        background: var(--white);
        border-radius: 999px;
        padding: 5px 12px;
        font: inherit;
        font-size: 12.5px;
        color: var(--ink-700);
        cursor: pointer;
        text-align: left;
      }
      .starter:hover:not(:disabled) {
        border-color: var(--cdg-green-600);
        color: var(--cdg-green-700);
      }
      .turn {
        display: flex;
        flex-direction: column;
        gap: 4px;
        align-items: flex-start;
      }
      .turn.mine {
        align-items: flex-end;
      }
      .who {
        font-size: 11px;
        text-transform: uppercase;
        letter-spacing: 0.05em;
        color: var(--ink-500);
      }
      .bubble {
        background: var(--ink-100);
        border-radius: 10px;
        padding: 10px 13px;
        font-size: 13.5px;
        line-height: 1.6;
        white-space: pre-wrap;
        max-width: 90%;
      }
      .turn.mine .bubble {
        background: var(--cdg-green-100);
        color: var(--cdg-green-800);
      }
      .thinking {
        color: var(--ink-500);
      }
      .link {
        border: none;
        background: none;
        padding: 0;
        font: inherit;
        font-size: 12px;
        color: var(--cdg-green-600);
        cursor: pointer;
        text-decoration: underline;
      }
      .composer {
        display: flex;
        gap: 8px;
        padding: 12px 18px;
        border-top: 1px solid var(--ink-200);
      }
      .composer input {
        flex: 1;
      }
    `,
  ],
})
export class AssistantPanelComponent {
  private readonly api = inject(ApiService);

  readonly claimId = input.required<number>();
  /** Shown under the heading, e.g. which unit's view the advice is written for. */
  readonly hint = input<string>('');

  /** Raised when the agent wants a drafted reply carried into the answer field. */
  readonly useAnswer = output<string>();

  readonly available = signal(false);
  readonly turns = signal<AssistantTurn[]>([]);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly starters = [
    'Summarise this complaint in three lines.',
    'What is the customer actually asking for?',
    'Draft an answer I can send.',
    'What is missing before I can decide?',
  ];

  constructor() {
    this.api.assistantAvailable().subscribe({
      next: (status) => this.available.set(status.available),
      error: () => this.available.set(false),
    });
  }

  send(text: string): void {
    const question = text.trim();
    if (!question || this.busy()) {
      return;
    }

    const conversation = [...this.turns(), { fromUser: true, text: question }];
    this.turns.set(conversation);
    this.draft.set('');
    this.error.set(null);
    this.busy.set(true);

    this.api.askAssistant(this.claimId(), conversation).subscribe({
      next: (response) => {
        this.turns.set([...conversation, { fromUser: false, text: response.reply }]);
        this.busy.set(false);
      },
      error: (failure) => {
        // Leave the question in the thread: the agent can retry without retyping it.
        this.error.set(failure?.error?.detail ?? 'The assistant could not answer just now.');
        this.busy.set(false);
      },
    });
  }
}
