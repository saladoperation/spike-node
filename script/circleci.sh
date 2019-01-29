#TODO rewrite this script in closh when closh becomes stable
#!/usr/bin/env bash
git clone -b develop https://github.com/schlepfilter/aid &&
cd aid &&
lein install &&
cd .. &&
git clone -b develop https://github.com/schlepfilter/frp &&
cd frp &&
lein install &&
cd .. &&
lein cljsbuild once main-prod &&
lein cljsbuild once renderer
