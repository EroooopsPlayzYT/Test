# Big-store access test (2026-11-13), from a US cloud server

Plain request, User-Agent Mozilla/5.0, one request per URL. Columns: store, page, HTTP status, bytes, server, steam-deck mentions, page title | block header
```
trendyol    home   403        0  cloudflare     steamdeck=0     | proxy-reason: blocked malicious bot
trendyol    robots 200    16163  cloudflare     steamdeck=0    User-agent: * User-agent: Googlebot Disallow: | 
trendyol    search 403     4548  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
hepsiburada home   403     1756  AkamaiGHost    steamdeck=0    Hepsiburada | Güvenlik | 
hepsiburada robots 403     1756  AkamaiGHost    steamdeck=0    Hepsiburada | Güvenlik | 
hepsiburada search 403     1755  AkamaiGHost    steamdeck=0    Hepsiburada | Güvenlik | 
n11         home   403     4543  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
n11         robots 403     4543  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
n11         search 403     4543  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
amazon      home   202        0  CloudFront     steamdeck=0     | x-amzn-waf-action: challenge
amazon      robots 200     6551  Server         steamdeck=0    User-agent: * Disallow: /dp/product-availabil | 
amazon      search 503     2863  Server         steamdeck=0    Üzgünüz | 
vatan       home   403     5360  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
vatan       robots 200     1770  cloudflare     steamdeck=0    User-agent: * Disallow: /ScriptResource.axd D | 
vatan       search 403     5438  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
teknosa     home   403     5661  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
teknosa     robots 403     4547  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
teknosa     search 403     5661  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
mediamarkt  home   403     5401  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
mediamarkt  robots 200     2609  cloudflare     steamdeck=0    Sitemap: https://www.mediamarkt.com.tr/sitema | 
mediamarkt  search 403     5493  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
pttavm      home   403     5188  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
pttavm      robots 403     4546  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
pttavm      search 403     5188  cloudflare     steamdeck=0    Attention Required! | Cloudflare | 
idefix      home   200   616052  volt-adc       steamdeck=0    Alışılmadık Alışveriş | idefix | 
idefix      robots 200     5158  volt-adc       steamdeck=0    User-agent: * Content-Signal: ai-train=no, se | 
idefix      search 308       21  volt-adc       steamdeck=0    /arama?q=steam%20deck | 
pazarama    home   403    14116  cloudflare     steamdeck=0    Cloudflare Verification | cf-mitigated: challenge
pazarama    robots 403    14168  cloudflare     steamdeck=0    Cloudflare Verification | cf-mitigated: challenge
pazarama    search 403    14213  cloudflare     steamdeck=0    Cloudflare Verification | cf-mitigated: challenge
itopya      home   403     5330  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
itopya      robots 403     5381  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
itopya      search 403     5444  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
incehesap   home   403     5354  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
incehesap   robots 200       94  cloudflare     steamdeck=0    User-agent: * Disallow: /uye/* Disallow: /aja | 
incehesap   search 403     5393  cloudflare     steamdeck=0    Just a moment... | cf-mitigated: challenge
```

Headless Chromium (unmodified, waits 6 s for JS challenges):
```
trendyol    home   403  steamdeck=0    
trendyol    search 403  steamdeck=0    Attention Required! | Cloudflare
hepsiburada home   403  steamdeck=0    Hepsiburada | Güvenlik
hepsiburada search 403  steamdeck=0    Hepsiburada | Güvenlik
n11         home   403  steamdeck=0    Attention Required! | Cloudflare
n11         search 403  steamdeck=0    Attention Required! | Cloudflare
amazon      home   200  steamdeck=0    Amazon.com.tr
amazon      search 503  steamdeck=0    Üzgünüz
vatan       home   403  steamdeck=0    Just a moment...
vatan       search 403  steamdeck=0    Just a moment...
teknosa     home   403  steamdeck=0    Attention Required! | Cloudflare
teknosa     search 403  steamdeck=0    Attention Required! | Cloudflare
mediamarkt  home   403  steamdeck=0    Just a moment...
mediamarkt  search 403  steamdeck=0    Just a moment...
pttavm      home   403  steamdeck=0    Attention Required! | Cloudflare
pttavm      search 403  steamdeck=0    Attention Required! | Cloudflare
idefix      home   200  steamdeck=2    Alışılmadık Alışveriş | idefix
idefix      search 200  steamdeck=0    
pazarama    home   403  steamdeck=0    Just a moment...
pazarama    search 403  steamdeck=0    Just a moment...
itopya      home   403  steamdeck=0    Just a moment...
itopya      search 403  steamdeck=0    Just a moment...
incehesap   home   403  steamdeck=0    Just a moment...
incehesap   search 403  steamdeck=0    Just a moment...
```
