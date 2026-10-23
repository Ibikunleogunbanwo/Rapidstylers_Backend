# Custom quotes for stylists: plan

## The idea in one line
A customer asks for something that has no fixed price (bridal party, waist-length knotless, a lace unit install, a whole-family visit). The stylist replies with a price, and the customer books and pays that price in one tap.

## Does it fit?
Yes, and it fits without changing how money moves. A booking already stores its own price (`servicePrice`, `travelFee`, `price`, `paymentAmount`). The Stripe hold, capture on accept, 12% fee and payout don't care where the price came from. A quote is just a booking whose price was agreed in advance instead of read from the service list.

## How it works for people
1. **The stylist marks a service "Price on quote"**, or the customer taps "Request a quote" on any profile. Either way the customer describes what they want and when suits them.
2. **The stylist gets a notification and an email.** They open the request and send back a price, a duration, an optional note ("includes hair, bring your own extensions"), and how long the quote stays valid (default 3 days).
3. **The customer sees the quote** and can accept it (this goes straight to the normal checkout with the price locked), ask a question, or decline.
4. **Accepting makes a normal booking.** The usual card hold, accept, complete and payout steps follow. The appointment shows "Custom quote" so both sides know where the price came from.

## What we build
**Backend**
- A new `quotes` table: id, customer, stylist, description, preferred dates, status (REQUESTED, QUOTED, ACCEPTED, DECLINED, EXPIRED, WITHDRAWN), price in cents, duration, note, valid-until, and the booking id once accepted.
- Endpoints:
  - customer: request a quote, list mine, accept, decline
  - stylist: list incoming, send a price, withdraw
- Accept calls the existing booking service with the quoted price and duration. The server re-checks that the quote is still valid and still belongs to that customer, so nobody can change the price from the browser.
- A quote past its date reads as EXPIRED straight away, so no job is needed and a stale price can never be booked.
- Notifications and emails go through the existing outbox.

**Frontend**
- "Request a quote" button on the stylist profile, plus a short form.
- A stylist dashboard page called **Quotes**, under the Bookings heading, with an unread count.
- Customer side: a "My quotes" tab with Accept, which opens the existing checkout with the price locked.
- A service can be set to "Price on quote" in the Services editor.

**Guard rails**
- A price floor and ceiling (for example $20 to $2,000) and a duration cap.
- Limit how many open requests a customer can send to stop spam.

## Size
About 2 to 3 focused working sessions: backend tables and endpoints first (with tests), then the stylist Quotes page, then the customer side and checkout hook-up. It can ship to the demo first and be tested end to end with Stripe test cards.

## Later, not now
- Reference photos on a request (customers can't upload photos yet)
- Back-and-forth messaging on a quote (start with one optional question and answer)
- Deposits or part payments for big jobs
- Multi-person or multi-visit packages
