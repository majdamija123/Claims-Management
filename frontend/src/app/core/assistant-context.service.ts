import { Injectable, signal } from '@angular/core';

/** The complaint the assistant should talk about, when one is on screen. */
export interface AssistantClaim {
  id: number;
  reference: string;
}

/**
 * What the floating assistant is currently looking at.
 *
 * <p>The widget lives in the application frame, so it outlives any one page. The complaint
 * page publishes the complaint it opened here, and clears it on the way out — which is what
 * lets one widget follow the user around while still advising on the record in front of them.
 */
@Injectable({ providedIn: 'root' })
export class AssistantContextService {
  /** The complaint in view, or null when the user is not on a complaint. */
  readonly claim = signal<AssistantClaim | null>(null);

  /**
   * An answer the assistant drafted, waiting for the complaint page to pick it up and seed
   * the completion dialog with it.
   */
  readonly draftAnswer = signal('');

  open(claim: AssistantClaim): void {
    const current = this.claim();
    if (current?.id !== claim.id) {
      // A different complaint: the previous conversation no longer applies.
      this.draftAnswer.set('');
    }
    this.claim.set(claim);
  }

  close(claimId: number): void {
    // Guarded, so a page being torn down after the next one loaded cannot clear its context.
    if (this.claim()?.id === claimId) {
      this.claim.set(null);
    }
  }
}
