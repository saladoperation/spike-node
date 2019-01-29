#TODO rewrite this script in closh when closh becomes stable
#!/usr/bin/env bash
git clone -b develop https://github.com/schlepfilter/aid &&
cd aid &&
lein install &&
cd .. &&
git clone -b develop https://github.com/schlepfilter/frp &&
cd aid &&
lein install &&
cd ..
