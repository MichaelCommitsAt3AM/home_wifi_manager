# Router investigation

Read-only inspection performed via a local in-app browser and authorized HTTP inspection. Credentials were supplied for the inspection. The initial investigation was read-only. A later owner-authorized Wi-Fi block/unblock validation is recorded below. No global filter-policy, reboot, firmware, Wi-Fi configuration, WAN, DNS, DHCP, or provisioning change was performed.

## Verified device and interface

- Huawei EchoLife HG8546M, hardware `767.A`, software `V3R017C10S125`, customization `COMMON`.
- Administration: `http://192.168.100.1/`, authenticated dashboard `/index.asp`, individual sections in iframes.
- Status includes device information, user devices, WLAN, DHCP, Ethernet, optical, WAN and provisioning sections. Security exposes separate wired and WLAN MAC filtering pages. User-device network application links lead to these pages according to connection type.
- Firmware reports online/offline, MAC, IP, hostname, port (LAN/SSID), connection type, DHCP/static source and client type. It includes offline entries. The initial deployed snapshot returned 16 unique identities and 12 online observations; these counts change over time.
- CPU and memory usage are present. Uptime is an empty string. No reliable Internet reachability signal or per-device traffic counter was verified. ONT registration O5 is not treated as proof of Internet connectivity.

## Authentication and expiry

Observed login source: `POST /asp/GetRandCount.asp` obtains a challenge. `POST /login.cgi` sends URL-encoded `UserName`, `PassWord` (Base64, **not encryption**), and `x.X_HW_Token`. The browser initializes a `Cookie` value describing language; the server assigns the authenticated cookie. Cookies and challenge values are deliberately omitted here.

Protected-page response with no cookie was tested: HTTP **200** with a “Waiting...” HTML page containing `var pageName = '/'; top.location.replace(pageName);`. This is a session-expiration signal, not a successful data response. The adapter recognizes it, renews once, then retries only the read. Bad credentials latch authentication failure until restart to prevent repeated lockout attempts. The login page advertises a three-attempt lockout and 60-second lock period; this was read from source, not triggered.

Session idle duration and router behavior with simultaneous administrative users have not been measured. Backend session operations are serialized. Routine polling starts at 60 seconds and backs off on failure.

## Device retrieval: verified

`POST /html/bbsp/common/GetLanUserDevInfo.asp`, empty body, authenticated session. Dashboard helper `/html/bbsp/common/lanuserinfo.asp` calls it through AJAX.

Response is executable-looking JavaScript, not JSON. Sanitized record:

```javascript
var UserDevinfo = new Array(
  new USERDevice("InternetGatewayDevice.LANDevice.1.X_HW_UserDev.1",
    "192\\x2e168\\x2e100\\x2e50", "02\\x3a00\\x3a00\\x3a00\\x3a00\\x3a02",
    "LAN1", "DHCP", "", "Online", "ETH", "0\\x3a10", "Example", "1", "1", "0"),
  null);
```

Field order: domain, IPv4, MAC, port, address source, client type, status, port type, connection time text, hostname, IPv4 enabled, IPv6 enabled, device type. The adapter parses literal constructors and escapes, never `eval`s router responses, validates identities, and deduplicates by MAC, preferring online observations. DHCP leases are not used to infer association. A randomized MAC is a separate identity; no unsupported identity merging is attempted.

## Access control: source verified, Wi-Fi mutation validated

The owner reports that blocking/unblocking works in this dashboard. We inspected these actual firmware pages and their form helpers:

| Scope | Page | Array | Object |
| --- | --- | --- | --- |
| Wired | `/html/bbsp/macfilter/macfilter.asp` | `MacFilter` | `InternetGatewayDevice.X_HW_Security.MacFilter` |
| Wi-Fi | `/html/bbsp/wlanmacfilter/wlanmacfilter.asp` | `MacFilterSrc` | `InternetGatewayDevice.X_HW_Security.WLANMacFilter` |

Both currently report `enableFilter='1'`, `Mode='0'` (enabled blacklist), with empty arrays. The adapter refuses disabled filtering or whitelist mode rather than changing global policy. Wired page limit is eight entries. WLAN scope includes SSID.

The firmware constructs relative form submissions inside each iframe:

- Add: `POST <filter-directory>/add.cgi?x=<object>&RequestFile=<page-without-leading-slash>`, with `x.SourceMACAddress`, fresh `x.X_HW_Token`; WLAN also adds `x.SSIDName=SSID-1` and `x.Enable=1`.
- Remove: `POST <filter-directory>/del.cgi?x=<object>&RequestFile=<page>`, body keys are the exact existing object instance domains, empty values, plus fresh `x.X_HW_Token`.
- Per-page token is an `onttoken` hidden input. It is retrieved immediately before mutation; never hardcoded.
- Wired array record: `new stMacFilter(domain, MACAddress)`. WLAN: `new stMacFilter(domain, SSIDName, MACAddress)`.

Live Wi-Fi add/remove was validated with explicit owner approval for one test phone on SSID1. The router confirmed a WLAN blacklist rule for SSID-1, reported the phone offline after a ten-second block, then confirmed deletion and exact restoration of the pre-test blacklist state. No other device was changed. Initial submissions returned an HTTP-200 firmware error page: matching the native form ordering, with x.X_HW_Token last, resolved the rejection. The adapter also supplies form Origin/Referer and preserves slash characters in RequestFile. Wired form handling has automated coverage but has not been live-tested. Successful HTTP status is never sufficient: the adapter rereads both blacklists and checks the requested MAC/scope/SSID. Ambiguous mutation timeouts are not retried automatically.

## Boundaries and limitations

- MAC filtering is interface/SSID dependent and does not prevent MAC spoofing or prove the device cannot communicate by another path.
- Stale observations and router offline state are visibly marked; failed polls do not mark the entire network disconnected.
- Critical identities discovered for this deployment (server, management PC and phone) are configured server-side and excluded from blocking. IP guards and persistent MAC guards both apply; changed phone randomization must be reviewed.
- No supported documented API, SNMP credentials, or management protocol was established. We do not scan or modify ISP-side management services.
- Bandwidth limits, traffic metering, firmware actions, Wi-Fi settings editing and router reboot are not exposed by the app.
- Additional SSIDs, whitelist policy, changed firmware and behavior of disabled individual WLAN rules need separate validation. The adapter intentionally rejects uncertain schemas and policies.
- Router-side HTTP exposes credentials and cookies to a trusted-LAN attacker. Android talks only authenticated HTTPS to the backend; the router UI is never proxied remotely.


## Session release

On 20 October 2026, inspected the firmware's `clickLogout` form handler: it submits `logout.cgi?RequestFile=html/logout.html` using POST. Live tests confirmed this releases the single dashboard login slot for another client. See [Router dashboard access](router-access.md) for the durable monitoring pause and explicit resume behavior.


## Total traffic inspection — 20 October 2026

Read-only inspection of the authenticated root dashboard, its menu, WAN/Ethernet/WLAN/optical status pages and referenced WAN data scripts found no usable total Internet byte counter. Monitoring was released for each inspection, diagnostic sessions were explicitly logged out, and normal server monitoring was restored.

- `/html/bbsp/waninfo/waninfo.asp`: PON statistics structure exposes packet fields, with empty values in this page.
- `/html/amp/opticinfo/opticinfo.asp`: PON transmitted/received packet counts are populated and change between observations. No byte counts exposed there. Packets cannot be converted accurately into bytes without their sizes; optical totals may also include non-Internet services.
- `/html/amp/ethinfo/ethinfo.asp`: `WANEthernetInterfaceConfig.Stats` exposes received/sent bytes and packets but all four values remain zero in repeated observations while LAN and PON counters advance. This Ethernet WAN interface does not provide a usable measurement for the current fiber connection.
- Same Ethernet page: four `LANPort.*.Statistics` records provide active transmitted/received byte and packet counts.
- `/html/amp/wlaninfo/wlaninfo.asp`: WLANConfiguration.1 provides active aggregate wireless sent/received byte and packet counters.
- Referenced `/html/bbsp/common/wan_list_info.asp`, `wan_list.asp` and `wan_control.asp`: no Internet byte/packet statistics fields found.

LAN/WLAN counters measure interface traffic, including local transfers. Summing them can count a local transfer at both interfaces and cannot be presented as total Internet use. Counter widths, reset behavior and wrap behavior were not validated. No undocumented shell, SNMP service, or alternate privileged account was enabled or tested. These findings establish the exposed dashboard's limitations, not that the firmware has no internal WAN counters.

No usage tracking was implemented. Reliable whole-home Internet usage requires a counter on the actual Internet forwarding path; DNS requests observed by AdGuard are not a measurement of transferred bytes.
