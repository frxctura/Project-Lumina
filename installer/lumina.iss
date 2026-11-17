; Inno Setup script for Lumina. Built by `gradlew installer`, which passes AppVersion and SourceDir.
#ifndef AppVersion
  #define AppVersion "dev"
#endif
#ifndef SourceDir
  #define SourceDir "..\build\jpackage\Lumina"
#endif

[Setup]
AppId={{6C1E9A52-3F0B-4B8E-9D6A-6A2F1F0B7C31}
AppName=Lumina
AppVersion={#AppVersion}
AppPublisher=frxctura
DefaultDirName={localappdata}\Programs\Lumina
DefaultGroupName=Lumina
DisableProgramGroupPage=yes
PrivilegesRequired=lowest
OutputDir=..\build\installer
OutputBaseFilename=LuminaSetup-{#AppVersion}
SetupIconFile=..\src\main\resources\com\javafxapp\javafxapp\images\icon.ico
UninstallDisplayIcon={app}\Lumina.exe
WizardStyle=modern dynamic
Compression=lzma2/ultra64
SolidCompression=yes
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
CloseApplications=yes

[Tasks]
Name: "desktopicon"; Description: "Create a &desktop shortcut"; GroupDescription: "Shortcuts:"

[Files]
Source: "{#SourceDir}\*"; DestDir: "{app}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{group}\Lumina"; Filename: "{app}\Lumina.exe"
Name: "{group}\Uninstall Lumina"; Filename: "{uninstallexe}"
Name: "{autodesktop}\Lumina"; Filename: "{app}\Lumina.exe"; Tasks: desktopicon

[Run]
Filename: "{app}\Lumina.exe"; Description: "Launch Lumina"; Flags: nowait postinstall skipifsilent
