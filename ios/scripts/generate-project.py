#!/usr/bin/env python3
"""Generate a dependency-free Xcode project; no XcodeGen/CocoaPods required."""
from pathlib import Path
import hashlib, json, plistlib, subprocess
ROOT = Path(__file__).resolve().parents[1]
# Keep user signing and bundle settings when regenerating the project.
previous = {}
project_file = ROOT/'IPv6Lab.xcodeproj/project.pbxproj'
if project_file.exists():
    old = json.loads(subprocess.check_output(['plutil','-convert','json','-o','-',str(project_file)]))['objects']
    for obj in old.values():
        if obj.get('isa') == 'PBXNativeTarget':
            for cid in old[obj['buildConfigurationList']]['buildConfigurations']:
                cfg = old[cid]
                previous[(obj['name'], cfg['name'])] = {k:v for k,v in cfg['buildSettings'].items() if k in ['DEVELOPMENT_TEAM','PRODUCT_BUNDLE_IDENTIFIER']}
objects = {}
def ident(name): return hashlib.sha1(name.encode()).hexdigest()[:24].upper()
def add(label, isa, **fields):
    key=ident(label); objects[key]={'isa':isa, **fields}; return key
def dump(value):
    if isinstance(value,dict): return '{\n'+''.join(f'{json.dumps(str(k))} = {dump(v)};\n' for k,v in value.items())+'}'
    if isinstance(value,list): return '(\n'+''.join(dump(v)+',\n' for v in value)+')'
    return json.dumps(str(value))
shared=[add(str(path.relative_to(ROOT)),'PBXFileReference',lastKnownFileType='sourcecode.swift',path=str(path.relative_to(ROOT)),sourceTree='<group>') for path in sorted((ROOT/'Shared').glob('*.swift'))]
appfile=add('appfile','PBXFileReference',lastKnownFileType='sourcecode.swift',path='App/IPv6LabApp.swift',sourceTree='<group>')
extfile=add('extfile','PBXFileReference',lastKnownFileType='sourcecode.swift',path='Tunnel/PacketTunnelProvider.swift',sourceTree='<group>')
app_extra = []
for path in sorted((ROOT/'App').rglob('*.swift')):
    if path.name != 'IPv6LabApp.swift':
        relative = str(path.relative_to(ROOT))
        app_extra.append(add(relative, 'PBXFileReference', lastKnownFileType='sourcecode.swift', path=relative, sourceTree='<group>'))
extra = []
for path in sorted((ROOT/'Tunnel').glob('*.swift')):
    if path.name != 'PacketTunnelProvider.swift':
        extra.append(add(str(path.relative_to(ROOT)), 'PBXFileReference', lastKnownFileType='sourcecode.swift', path=str(path.relative_to(ROOT)), sourceTree='<group>'))
for path in sorted((ROOT/'Native').glob('*.c')) + sorted((ROOT.parent/'third_party/hev-socks5-tunnel/third-part/lwip/src/core').rglob('*.c')):
    relative = str(path.relative_to(ROOT)) if path.is_relative_to(ROOT) else '../'+str(path.relative_to(ROOT.parent))
    extra.append(add(relative,'PBXFileReference',lastKnownFileType='sourcecode.c.c',path=relative,sourceTree='<group>'))
licensefile=add('lwip-license','PBXFileReference',lastKnownFileType='text',path='../third_party/hev-socks5-tunnel/third-part/lwip/LICENSE',sourceTree='<group>')
products=[]; targets=[]
projectID=ident('project')
for name,bundle,folder,source,productType,extension in [
 ('IPv6Lab','dev.ipv6lab.ios','App',appfile,'com.apple.product-type.application','app'),
 ('PacketTunnel','dev.ipv6lab.ios.PacketTunnel','Tunnel',extfile,'com.apple.product-type.app-extension','appex')]:
    product=add(name+'product','PBXFileReference',explicitFileType='wrapper.application' if extension=='app' else 'wrapper.app-extension',path=name+'.'+extension,sourceTree='BUILT_PRODUCTS_DIR',includeInIndex=0)
    products.append(product)
    buildfiles=[add(name+f,'PBXBuildFile',fileRef=f) for f in ([source]+shared + (extra if extension=='appex' else app_extra))]
    sources=add(name+'sources','PBXSourcesBuildPhase',buildActionMask=2147483647,files=buildfiles,runOnlyForDeploymentPostprocessing=0)
    frameworks=add(name+'frameworks','PBXFrameworksBuildPhase',buildActionMask=2147483647,files=[],runOnlyForDeploymentPostprocessing=0)
    resources=add(name+'resources','PBXResourcesBuildPhase',buildActionMask=2147483647,files=[add(name+'license','PBXBuildFile',fileRef=licensefile)],runOnlyForDeploymentPostprocessing=0)
    configs=[]
    for config in ['Debug','Release']:
        settings={'PRODUCT_BUNDLE_IDENTIFIER':bundle,'PRODUCT_NAME':'$(TARGET_NAME)',
            'INFOPLIST_FILE':folder+'/Info.plist','CODE_SIGN_ENTITLEMENTS':folder+'/IPv6Lab.entitlements',
            'CODE_SIGN_STYLE':'Automatic','SWIFT_VERSION':'5.0','IPHONEOS_DEPLOYMENT_TARGET':'16.4',
            'TARGETED_DEVICE_FAMILY':'1,2','SDKROOT':'iphoneos','SUPPORTED_PLATFORMS':'iphoneos iphonesimulator',
            'SUPPORTS_MACCATALYST':'NO','GENERATE_INFOPLIST_FILE':'NO',
            'SWIFT_OPTIMIZATION_LEVEL':'-Onone' if config=='Debug' else '-O',
            'LD_RUNPATH_SEARCH_PATHS':'$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks',
            'CURRENT_PROJECT_VERSION':'2','MARKETING_VERSION':'0.2.0',
            'ENABLE_USER_SCRIPT_SANDBOXING':'YES'}
        if config=='Debug': settings['SWIFT_ACTIVE_COMPILATION_CONDITIONS']='$(inherited) DEBUG'
        if extension=='appex':
            settings.update(APPLICATION_EXTENSION_API_ONLY='YES',SKIP_INSTALL='YES',
                SWIFT_OBJC_BRIDGING_HEADER='Native/LabStack.h', OTHER_CFLAGS=['$(inherited)','-fno-modules'],
                OTHER_LDFLAGS=['$(inherited)','-lresolv'],
                HEADER_SEARCH_PATHS=['$(SRCROOT)/Native/include','$(SRCROOT)/../third_party/hev-socks5-tunnel/third-part/lwip/src/include'])
        settings.update(previous.get((name,config), {}))
        configs.append(add(name+config,'XCBuildConfiguration',name=config,buildSettings=settings))
    configlist=add(name+'configs','XCConfigurationList',buildConfigurations=configs,defaultConfigurationIsVisible=0,defaultConfigurationName='Debug')
    targets.append(add(name,'PBXNativeTarget',name=name,productName=name,productReference=product,productType=productType,
                       buildConfigurationList=configlist,buildPhases=[sources,frameworks,resources],buildRules=[],dependencies=[]))
    info={'CFBundleDevelopmentRegion':'en','CFBundleExecutable':'$(EXECUTABLE_NAME)','CFBundleIdentifier':'$(PRODUCT_BUNDLE_IDENTIFIER)',
          'CFBundleInfoDictionaryVersion':'6.0','CFBundleName':'$(PRODUCT_NAME)','CFBundlePackageType':'APPL' if extension=='app' else 'XPC!',
          'CFBundleShortVersionString':'$(MARKETING_VERSION)','CFBundleVersion':'$(CURRENT_PROJECT_VERSION)',
          'NSLocalNetworkUsageDescription':'允许本地 VPN 访问局域网测试目标和 DNS/TCP/UDP 回显服务。'}
    if extension=='app': info.update(CFBundleDisplayName='IPv6 Lab',UILaunchScreen={},UISupportedInterfaceOrientations=['UIInterfaceOrientationPortrait','UIInterfaceOrientationPortraitUpsideDown','UIInterfaceOrientationLandscapeLeft','UIInterfaceOrientationLandscapeRight'])
    else: info['NSExtension']={'NSExtensionPointIdentifier':'com.apple.networkextension.packet-tunnel','NSExtensionPrincipalClass':'$(PRODUCT_MODULE_NAME).PacketTunnelProvider'}
    (ROOT/folder/'Info.plist').write_bytes(plistlib.dumps(info))
    (ROOT/folder/'IPv6Lab.entitlements').write_bytes(plistlib.dumps({'com.apple.developer.networking.networkextension':['packet-tunnel-provider']}))
proxy=add('proxy','PBXContainerItemProxy',containerPortal=projectID,proxyType=1,remoteGlobalIDString=targets[1],remoteInfo='PacketTunnel')
dep=add('dependency','PBXTargetDependency',target=targets[1],targetProxy=proxy)
embedfile=add('embedfile','PBXBuildFile',fileRef=products[1],settings={'ATTRIBUTES':['RemoveHeadersOnCopy']})
embed=add('embed','PBXCopyFilesBuildPhase',buildActionMask=2147483647,dstPath='',dstSubfolderSpec=13,files=[embedfile],name='Embed App Extensions',runOnlyForDeploymentPostprocessing=0)
objects[targets[0]]['dependencies']=[dep]; objects[targets[0]]['buildPhases'].append(embed)
prodgroup=add('products','PBXGroup',children=products,name='Products',sourceTree='<group>')
main=add('main','PBXGroup',children=[appfile,extfile,prodgroup,licensefile]+shared+app_extra+extra,sourceTree='<group>')
configs=[add('project'+c,'XCBuildConfiguration',name=c,buildSettings={'CLANG_ENABLE_MODULES':'YES','CLANG_ENABLE_OBJC_ARC':'YES','DEBUG_INFORMATION_FORMAT':'dwarf','ENABLE_TESTABILITY':'YES' if c=='Debug' else 'NO'}) for c in ['Debug','Release']]
configlist=add('projectconfigs','XCConfigurationList',buildConfigurations=configs,defaultConfigurationIsVisible=0,defaultConfigurationName='Debug')
add('project','PBXProject',attributes={'LastUpgradeCheck':'1610','TargetAttributes':{t:{'CreatedOnToolsVersion':'16.1','SystemCapabilities':{'com.apple.NetworkExtensions.iOS':{'enabled':1}}} for t in targets}},buildConfigurationList=configlist,compatibilityVersion='Xcode 14.0',developmentRegion='en',knownRegions=['en','Base'],mainGroup=main,productRefGroup=prodgroup,projectDirPath='',projectRoot='',targets=targets)
proj=ROOT/'IPv6Lab.xcodeproj';proj.mkdir(exist_ok=True)
(proj/'project.pbxproj').write_text('// !$*UTF8*$!\n'+dump({'archiveVersion':1,'classes':{},'objectVersion':56,'objects':objects,'rootObject':projectID})+'\n')
schemes=proj/'xcshareddata/xcschemes';schemes.mkdir(parents=True,exist_ok=True)
ref=f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{targets[0]}" BuildableName="IPv6Lab.app" BlueprintName="IPv6Lab" ReferencedContainer="container:IPv6Lab.xcodeproj"/>'
(schemes/'IPv6Lab.xcscheme').write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1610" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{ref}</BuildActionEntry></BuildActionEntries></BuildAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{ref}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>''')
print(proj)
