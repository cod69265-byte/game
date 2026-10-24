import re,sys,os
files=[
 r"C:\Users\oo\Desktop\自己\veg3hd_ios\ios-port\ios\robovm.xml",
 r"C:\Users\oo\Desktop\自己\veg3hd_ios\ios-port\ios\Info.plist.xml",
 r"C:\Users\oo\Desktop\自己\veg3hd_ios\ios-port\ios26\robovm-ios26.xml",
 r"C:\Users\oo\Desktop\自己\veg3hd_ios\ios-port\ios26\Info.plist.xml",
]
VALID={"app.executable","app.id","app.name"}
for f in files:
    txt=open(f,encoding="utf-8").read()
    # 去掉 XML 注释
    stripped=re.sub(r"<!--.*?-->","",txt,flags=re.S)
    found=sorted(set(re.findall(r"\$\{([A-Za-z0-9_.]+)\}",stripped)))
    bad=[x for x in found if x not in VALID]
    tag=os.path.basename(os.path.dirname(f))+"/"+os.path.basename(f)
    print("  %-28s found=%s  INVALID=%s" % (tag, found or "[]", bad or "none"))
