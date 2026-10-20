# Timed device blocks

Device details offers Block for… shortcuts: 15 minutes, 1 hour or 4 hours. The API accepts 1–1440 minutes. After confirmation, the backend checks the current router association and protections, requires that no restriction already exists for the MAC, and saves a one-time automatic unblock before sending the block request. Other active schedules for that MAC are paused, as with manual block/unblock. Successful blocking is verified by the existing adapter's readback.

Recovery actions use the existing Schedule table with timed_pending, timed_active and timed_retry statuses. A request whose router result is ambiguous retains the saved unblock intent. Refresh actual state before retrying; the app does not blindly repeat router writes.

Unlike ordinary missed schedules, overdue timed unblocks are not discarded. They retry every minute when the router is unavailable and run after backend recovery. During Release router access, they wait until access is resumed. Consequently, the displayed time is an intended execution time, not a guarantee of restoration while the server/router is unavailable.

Use Unblock device to end a timed block early. The recovery schedule cannot be cancelled on its own, and new schedules for that device require ending its timed block first. Critical-device protection and existing SSID/interface scope limitations still apply. The app does not temporarily override a pre-existing manual restriction.
