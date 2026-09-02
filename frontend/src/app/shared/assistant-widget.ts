import { ChangeDetectionStrategy, Component, computed, effect, inject, signal } from '@angular/core';
import { FormsModule } from '@angular/forms';
import { ApiService, AssistantTurn } from '../core/api.service';
import { AssistantContextService } from '../core/assistant-context.service';

/**
 * The assistant, as a button in the corner of every page and a panel that opens above it.
 *
 * <p>It follows the user rather than living on one screen: it advises on whichever complaint
 * is open, and says so plainly when none is. The conversation is dropped when the user moves
 * to a different complaint, since the advice was about the previous one.
 *
 * <p>Hidden entirely when the assistant is not configured on the server, rather than offering
 * a button that cannot work.
 */
@Component({
  selector: 'app-assistant-widget',
  changeDetection: ChangeDetectionStrategy.OnPush,
  imports: [FormsModule],
  template: `
    @if (available()) {
      <div class="assistant-dock">
        @if (open()) {
          <section class="panel">
            <header>
              <div class="title">
                <img src="cdg-logo-white.svg" alt="" class="mark" />
                <div>
                  <strong>Assistant</strong>
                  <span>{{ subtitle() }}</span>
                </div>
              </div>
              <button class="close" (click)="open.set(false)" aria-label="Close">×</button>
            </header>

            <div class="thread">
              @if (!claim()) {
                <p class="empty-note">
                  Open a complaint and I can summarise it, tell you what is missing, or draft
                  the answer for your unit.
                </p>
              } @else if (turns().length === 0) {
                <p class="empty-note">Ask about {{ claim()!.reference }}, or start with:</p>
                @for (starter of starters; track starter) {
                  <button class="starter" (click)="send(starter)" [disabled]="busy()">
                    {{ starter }}
                  </button>
                }
              }

              @for (turn of turns(); track $index) {
                <div class="turn" [class.mine]="turn.fromUser">
                  <div class="bubble">{{ turn.text }}</div>
                  @if (!turn.fromUser) {
                    <button class="use" (click)="useAsAnswer(turn.text)">
                      Use this as the answer
                    </button>
                  }
                </div>
              }

              @if (busy()) {
                <div class="turn"><div class="bubble thinking">Thinking…</div></div>
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
                [placeholder]="claim() ? 'Ask about this complaint…' : 'Open a complaint first'"
                [disabled]="busy() || !claim()"
              />
              <button
                class="btn primary sm"
                type="submit"
                [disabled]="busy() || !claim() || !draft().trim()"
              >
                Ask
              </button>
            </form>
          </section>
        }

        <button
          class="launcher"
          [class.active]="open()"
          (click)="open.set(!open())"
          [attr.aria-label]="open() ? 'Close the assistant' : 'Open the assistant'"
        >
          <img src="cdg-logo-white.svg" alt="" />
        </button>
      </div>
    }
  `,
  styles: [
    `
      .assistant-dock {
        position: fixed;
        right: 22px;
        bottom: 22px;
        z-index: 60;
        display: flex;
        flex-direction: column;
        align-items: flex-end;
        gap: 12px;
      }

      .launcher {
        width: 56px;
        height: 56px;
        border: none;
        border-radius: 50%;
        background: var(--cdg-green-700);
        box-shadow: var(--shadow-lg);
        cursor: pointer;
        display: flex;
        align-items: center;
        justify-content: center;
        flex: none;
        transition: background 0.15s ease, transform 0.15s ease;
      }
      .launcher:hover {
        background: var(--cdg-green-800);
        transform: translateY(-1px);
      }
      .launcher.active {
        background: var(--cdg-green-800);
      }
      .launcher img {
        width: 30px;
        height: auto;
      }

      .panel {
        width: min(400px, calc(100vw - 44px));
        max-height: min(560px, calc(100vh - 130px));
        background: var(--white);
        border: 1px solid var(--ink-200);
        border-radius: var(--radius-lg);
        box-shadow: var(--shadow-lg);
        display: flex;
        flex-direction: column;
        overflow: hidden;
      }

      header {
        display: flex;
        align-items: center;
        justify-content: space-between;
        gap: 10px;
        padding: 12px 14px;
        background: var(--cdg-green-700);
        color: #fff;
      }
      .title {
        display: flex;
        align-items: center;
        gap: 10px;
        min-width: 0;
      }
      .title .mark {
        width: 26px;
        height: auto;
        flex: none;
      }
      .title strong {
        display: block;
        font-size: 14px;
        line-height: 1.2;
      }
      .title span {
        display: block;
        font-size: 11.5px;
        color: #cfe2ab;
      }
      .close {
        border: none;
        background: transparent;
        color: #fff;
        font-size: 21px;
        line-height: 1;
        cursor: pointer;
        padding: 0 4px;
      }

      .thread {
        flex: 1;
        padding: 14px;
        display: flex;
        flex-direction: column;
        align-items: flex-start;
        gap: 10px;
        overflow-y: auto;
      }
      .empty-note {
        color: var(--ink-500);
        font-size: 12.5px;
        line-height: 1.6;
        margin: 0;
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
        max-width: 100%;
      }
      .turn.mine {
        align-self: flex-end;
        align-items: flex-end;
      }
      .bubble {
        background: var(--ink-100);
        border-radius: 10px;
        padding: 9px 12px;
        font-size: 13px;
        line-height: 1.6;
        white-space: pre-wrap;
      }
      .turn.mine .bubble {
        background: var(--cdg-green-100);
        color: var(--cdg-green-800);
      }
      .thinking {
        color: var(--ink-500);
      }
      .use {
        border: none;
        background: none;
        padding: 0;
        font: inherit;
        font-size: 11.5px;
        color: var(--cdg-green-600);
        cursor: pointer;
        text-decoration: underline;
      }

      .composer {
        display: flex;
        gap: 8px;
        padding: 10px 12px;
        border-top: 1px solid var(--ink-200);
      }
      .composer input {
        flex: 1;
        min-height: 34px;
      }

      @media (max-width: 620px) {
        .assistant-dock {
          right: 14px;
          bottom: 14px;
        }
      }
    `,
  ],
})
export class AssistantWidgetComponent {
  private readonly api = inject(ApiService);
  private readonly context = inject(AssistantContextService);

  readonly claim = this.context.claim;
  readonly available = signal(false);
  readonly open = signal(false);
  readonly turns = signal<AssistantTurn[]>([]);
  readonly draft = signal('');
  readonly busy = signal(false);
  readonly error = signal<string | null>(null);

  readonly subtitle = computed(() => {
    const claim = this.claim();
    return claim ? claim.reference : 'No complaint open';
  });

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

    // Advice given about one complaint says nothing about the next, so the thread starts over.
    let lastClaimId: number | null = null;
    effect(() => {
      const id = this.claim()?.id ?? null;
      if (id !== lastClaimId) {
        lastClaimId = id;
        this.turns.set([]);
        this.error.set(null);
        this.draft.set('');
      }
    });
  }

  send(text: string): void {
    const question = text.trim();
    const claim = this.claim();
    if (!question || !claim || this.busy()) {
      return;
    }

    const conversation = [...this.turns(), { fromUser: true, text: question }];
    this.turns.set(conversation);
    this.draft.set('');
    this.error.set(null);
    this.busy.set(true);

    this.api.askAssistant(claim.id, conversation).subscribe({
      next: (response) => {
        this.turns.set([...conversation, { fromUser: false, text: response.reply }]);
        this.busy.set(false);
      },
      error: (failure) => {
        // The question stays in the thread, so a retry needs no retyping.
        this.error.set(failure?.error?.detail ?? 'The assistant could not answer just now.');
        this.busy.set(false);
      },
    });
  }

  /** Hands the draft to the complaint page, which seeds the completion dialog with it. */
  useAsAnswer(text: string): void {
    this.context.draftAnswer.set(text);
    this.open.set(false);
  }
}
