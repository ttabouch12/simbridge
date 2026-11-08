# Use SimBridge away from home

By default SimBridge only works on the same Wi-Fi (or the SIM phone's hotspot): the gateway refuses
anything from the internet or mobile data. To use it from anywhere, put both phones on your own
private network (VPN). Your calls and texts stay encrypted end to end (TLS + SRTP) inside it.

## Tailscale (easiest)

1. Install [Tailscale](https://tailscale.com/download/android) on **both** phones and sign in with
   the same account. Keep it on (on the SIM phone, set it to *Always-on VPN* in Android settings).
2. In Tailscale on the SIM phone, note its address (`100.x.y.z`).
3. On the main phone: SimBridge → Settings → *Contacts & remote access* → **SIM phone VPN address**
   → enter `100.x.y.z` → Save.
4. Done. At home SimBridge still finds the SIM phone directly; away, it uses the VPN address.

The gateway accepts Tailscale addresses (`100.64.0.0/10`) because they are private and arrive over
the VPN interface, not mobile data.

## WireGuard

Same idea with your own WireGuard server: give both phones a private address (for example
`10.8.0.2` and `10.8.0.3`), make sure they can reach each other, and enter the SIM phone's VPN
address in SimBridge. Don't forward ports 5063/5064 on your router to the SIM phone.

## Battery

A VPN on the SIM phone costs a little battery. Tailscale and WireGuard are light; keep the
SIM phone on a charger if you can.
