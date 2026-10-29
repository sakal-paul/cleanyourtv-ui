import sys, json
from fontTools.ttLib import TTFont
from fontTools import subset
from fontTools.varLib import instancer
src, cpfile, out, mapout = sys.argv[1:5]
cps = {l.split()[0]: int(l.split()[1], 16) for l in open(cpfile)}
M = {"apps":"apps","bedtime":"bedtime","bell":"notifications","bitcoin":"currency_bitcoin","check":"check",
 "chevron_down":"keyboard_arrow_down","chevron_right":"chevron_right","clear_night":"clear_night","cloud":"cloud",
 "foggy":"foggy","gamepad":"stadia_controller","hdmi":"settings_input_hdmi","home":"home","live_tv":"live_tv",
 "partly_cloudy_day":"partly_cloudy_day","partly_cloudy_night":"partly_cloudy_night","play":"play_arrow","rainy":"rainy",
 "search":"search","settings":"settings","snowflake":"ac_unit","sparkles":"auto_awesome","speaker":"speaker","speed":"speed",
 "sunny":"sunny","sunrise":"wb_twilight","sunset":"wb_twilight","sync":"sync","thunderstorm":"thunderstorm","tune":"tune",
 "tv":"tv","tv_settings":"tv_options_input_settings","wifi":"wifi"}
OUTLINED = {"tv"}
f = TTFont(src, lazy=False)
opts = subset.Options(); opts.layout_features = []; opts.notdef_outline = True; opts.name_IDs = ["*"]
s = subset.Subsetter(opts); s.populate(unicodes=sorted({cps[v] for v in M.values()})); s.subset(f)
f = instancer.instantiateVariableFont(f, {"GRAD": 0, "opsz": 24, "wght": 500})
f.save(out)
json.dump({k: [cps[v], 0 if k in OUTLINED else 1] for k, v in M.items()}, open(mapout, "w"))
print([a.axisTag for a in f["fvar"].axes], "glyphs", len(f.getGlyphOrder()))
