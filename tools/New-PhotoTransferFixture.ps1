param(
    [string]$OutputPath = (Join-Path $PSScriptRoot '..\app\build\reports\photo-transfer\infocontainer-prueba.png')
)

# Deterministic, synthetic test picture. Never reads a user photograph or contacts a service.
Add-Type -AssemblyName System.Drawing
$fixtureOutput = [System.IO.Path]::GetFullPath($OutputPath)
if (Test-Path -LiteralPath $fixtureOutput) {
    throw "El archivo ya existe. Elige una ruta nueva: $fixtureOutput"
}
[System.IO.Directory]::CreateDirectory([System.IO.Path]::GetDirectoryName($fixtureOutput)) | Out-Null
$fixtureBitmap = New-Object System.Drawing.Bitmap 640,400
$fixtureGraphics = [System.Drawing.Graphics]::FromImage($fixtureBitmap)
$fixtureFont = New-Object System.Drawing.Font 'Arial',22
$fixtureSmallFont = New-Object System.Drawing.Font 'Arial',14
try {
    $fixtureGraphics.Clear([System.Drawing.Color]::White)
    $fixtureGraphics.FillRectangle([System.Drawing.Brushes]::Navy, 24, 24, 592, 100)
    $fixtureGraphics.DrawString('INFOCONTAINER', $fixtureFont, [System.Drawing.Brushes]::White, 45, 52)
    $fixtureGraphics.FillRectangle([System.Drawing.Brushes]::Orange, 40, 165, 160, 110)
    $fixtureGraphics.FillRectangle([System.Drawing.Brushes]::Teal, 240, 165, 160, 110)
    $fixtureGraphics.FillEllipse([System.Drawing.Brushes]::RoyalBlue, 455, 165, 110, 110)
    $fixtureGraphics.DrawString('PRUEBA SINTETICA - SIN DATOS REALES', $fixtureSmallFont, [System.Drawing.Brushes]::Black, 42, 325)
    $fixtureBitmap.Save($fixtureOutput, [System.Drawing.Imaging.ImageFormat]::Png)
} finally {
    $fixtureSmallFont.Dispose()
    $fixtureFont.Dispose()
    $fixtureGraphics.Dispose()
    $fixtureBitmap.Dispose()
}
$fixtureInfo = Get-Item -LiteralPath $fixtureOutput
[pscustomobject]@{
    Path = $fixtureInfo.FullName
    ByteSize = $fixtureInfo.Length
    Sha256 = (Get-FileHash -LiteralPath $fixtureOutput -Algorithm SHA256).Hash
}
