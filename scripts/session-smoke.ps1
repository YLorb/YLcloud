<# Uses a dedicated existing test account. Revokes ALL sessions of that account.
   Credentials are prompted securely; no password or Cookie is printed.
   Run only against a migrated test deployment, with HTTPS except local development. #>
param(
    [Parameter(Mandatory=$true)][string]$BaseUrl,
    [Parameter(Mandatory=$true)][PSCredential]$Credential,
    [Parameter(Mandatory=$true)][switch]$ConfirmTestAccount
)
$ErrorActionPreference = 'Stop'
if (-not $ConfirmTestAccount) { throw 'Confirm use of a dedicated test account.' }
$origin = [Uri]$BaseUrl
if ($origin.Scheme -ne 'https' -and -not $origin.IsLoopback) { throw 'HTTPS is required outside localhost.' }
$BaseUrl = $BaseUrl.TrimEnd('/')
function Invoke-SessionRequest($Method, $Path, $Session, $Body, [switch]$WithoutCsrf) {
    $headers = @{ Accept = 'application/json' }
    if (-not $WithoutCsrf) { $headers['X-YLCloud-Request'] = '1' }
    $params = @{ Uri = "$BaseUrl$Path"; Method = $Method; WebSession = $Session; Headers = $headers; UseBasicParsing = $true; MaximumRedirection = 0 }
    if ($null -ne $Body) { $params.ContentType = 'application/json'; $params.Body = $Body }
    try {
        $response = Invoke-WebRequest @params
        return [int]$response.StatusCode
    } catch {
        if ($null -ne $_.Exception.Response) { return [int]$_.Exception.Response.StatusCode }
        throw
    }
}
function Expect-Status($Actual, $Expected, $Step) {
    if ($Actual -ne $Expected) { throw "$Step expected HTTP $Expected, received $Actual" }
    Write-Host "PASS: $Step"
}
$first = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$second = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$replay = New-Object Microsoft.PowerShell.Commands.WebRequestSession
$body = @{ username = $Credential.UserName; password = $Credential.GetNetworkCredential().Password; rememberMe = $true } | ConvertTo-Json -Compress
try {
    Expect-Status (Invoke-SessionRequest POST /api/login $first $body -WithoutCsrf) 403 'login CSRF protection'
    Expect-Status (Invoke-SessionRequest POST /api/login $first $body) 200 'first login'
    Expect-Status (Invoke-SessionRequest GET /api/session $first $null) 200 'restore first session'
    foreach ($cookie in $first.Cookies.GetCookies($origin)) {
        $replay.Cookies.SetCookies($origin, "$($cookie.Name)=$($cookie.Value); Path=/")
    }
    Expect-Status (Invoke-SessionRequest POST /api/logout $first $null -WithoutCsrf) 403 'logout CSRF protection'
    Expect-Status (Invoke-SessionRequest POST /api/logout $first $null) 200 'logout'
    Expect-Status (Invoke-SessionRequest GET /api/session $replay $null) 401 'revoked cookie replay denied'
    Expect-Status (Invoke-SessionRequest POST /api/login $first $body) 200 'first device login'
    Expect-Status (Invoke-SessionRequest POST /api/login $second $body) 200 'second device login'
    Expect-Status (Invoke-SessionRequest POST /api/logout-all $first $null) 200 'revoke all devices'
    Expect-Status (Invoke-SessionRequest GET /api/session $second $null) 401 'second device revoked'
} finally {
    $body = $null
    # Best-effort cleanup if an assertion fails before logout-all.
    $null = Invoke-SessionRequest POST /api/logout $first $null
    $null = Invoke-SessionRequest POST /api/logout $second $null
}
