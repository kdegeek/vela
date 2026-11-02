# Initial coordinator phone checks

2026-10-31. The physical Shield remains paused and was not used.

The coordinator independently ran all 36 phone behavior tests and the management
callback/JVM-signature audit successfully. The phone script differs from the
committed version only in the two allowed presentation assignments; existing
controls retain their order and all five forms and 12 hidden attributes remain.

The actual changed `phone.html` was served read-only on loopback port 18098 and
observed in native Safari through the **Nate’s Mac mini** CUA Space. The page was
disconnected from a TV; local static-server 404 responses account for the error
text. These are appearance/navigation checks, not connected API acceptance.

Observed in Night:

- A 1280-point-wide test window showed the wide two-column arrangement.
- Safari's actual iPhone preset reported **393 × 852 CSS pixels**, displayed at
  82% fit. The page rendered a single column, the remote kept its directional
  geometry, and the Remote link scrolled its heading below the sticky header.
- The temporary test tab was moved to its own window. The original Safari window
  returned to its original title and retained its original size.

Entering 390/844 into Responsive Design Mode using AXValue/AXConfirm changed the
field labels but did **not** resize the rendered viewport. That attempt is not
390-pixel evidence. The native preset did visibly resize it. Background keyboard
delivery was refused with `same_pid_keyboard_ambiguity`; no mobile-width or
keyboard acceptance is inferred from that refusal.

Raw app-window captures remain private under `/tmp/vela-phone-*.png`; browser
chrome includes personal bookmark titles, so those captures are not committed.
Web Inspector and the Vela test tab were closed, the static server was stopped,
and the named CUA sessions ended. Safari retained an empty Untitled test window;
its closure was not established by the cleanup readback.

Still pending: fixture-backed connected/working/approval/error/editor states;
Day, high contrast, reduced motion, fallback rendering, 200% zoom and keyboard
interaction; exact 320/390/768/1280 CSS widths; and full combined Android validation.
None of these checks authorizes physical Shield interaction.
