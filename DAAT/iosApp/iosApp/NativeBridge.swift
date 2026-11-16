import AuthenticationServices
import CryptoKit
import FirebaseAuth
import FirebaseCore
import GoogleSignIn
import Shared
import UIKit
import UserNotifications

/// Sign-in and notification permission, done with Apple's and Google's own SDKs.
/// The shared Kotlin code calls these through `IosNativeBridge`; it sees the
/// result as a change in Firebase's signed-in user.
final class NativeBridge: NSObject, IosNativeBridge {
    private var appleSignIn: AppleSignIn?

    private var presenter: UIViewController? {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        var top = scene?.windows.first(where: { $0.isKeyWindow })?.rootViewController
        while let next = top?.presentedViewController { top = next }
        return top
    }

    // MARK: Google

    func signInWithGoogle(completion: @escaping (String?) -> Void) {
        guard let clientID = FirebaseApp.app()?.options.clientID, let presenter = presenter else {
            completion("Google sign-in isn't set up")
            return
        }
        GIDSignIn.sharedInstance.configuration = GIDConfiguration(clientID: clientID)
        GIDSignIn.sharedInstance.signIn(withPresenting: presenter) { result, error in
            if let error = error as NSError? {
                completion(error.code == GIDSignInError.canceled.rawValue ? "CANCELLED" : error.localizedDescription)
                return
            }
            guard let user = result?.user, let idToken = user.idToken?.tokenString else {
                completion("Google didn't return a token")
                return
            }
            let credential = GoogleAuthProvider.credential(withIDToken: idToken, accessToken: user.accessToken.tokenString)
            Auth.auth().signIn(with: credential) { _, error in
                completion(error?.localizedDescription)
            }
        }
    }

    // MARK: Apple

    func signInWithApple(completion: @escaping (String?) -> Void) {
        let flow = AppleSignIn { [weak self] error in
            self?.appleSignIn = nil
            completion(error)
        }
        appleSignIn = flow
        flow.start()
    }

    // MARK: Notifications

    func requestNotifications() {
        UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge]) { _, _ in
            DispatchQueue.main.async {
                UIApplication.shared.registerForRemoteNotifications()
            }
        }
    }
}

/// One run of Sign in with Apple, ending in a Firebase sign-in.
private final class AppleSignIn: NSObject, ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    private let completion: (String?) -> Void
    private let nonce = AppleSignIn.randomNonce()

    init(completion: @escaping (String?) -> Void) {
        self.completion = completion
    }

    func start() {
        let request = ASAuthorizationAppleIDProvider().createRequest()
        request.requestedScopes = [.fullName]
        // Apple gets the hash; Firebase later checks it against the raw value.
        request.nonce = SHA256.hash(data: Data(nonce.utf8)).map { String(format: "%02x", $0) }.joined()
        let controller = ASAuthorizationController(authorizationRequests: [request])
        controller.delegate = self
        controller.presentationContextProvider = self
        controller.performRequests()
    }

    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        return scene?.windows.first(where: { $0.isKeyWindow }) ?? ASPresentationAnchor()
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithAuthorization authorization: ASAuthorization) {
        guard let apple = authorization.credential as? ASAuthorizationAppleIDCredential,
              let tokenData = apple.identityToken,
              let token = String(data: tokenData, encoding: .utf8) else {
            completion("Apple didn't return a token")
            return
        }
        let credential = OAuthProvider.appleCredential(withIDToken: token, rawNonce: nonce, fullName: apple.fullName)
        Auth.auth().signIn(with: credential) { [completion] _, error in
            completion(error?.localizedDescription)
        }
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: Error) {
        let cancelled = (error as? ASAuthorizationError)?.code == .canceled
        completion(cancelled ? "CANCELLED" : error.localizedDescription)
    }

    private static func randomNonce(length: Int = 32) -> String {
        let alphabet = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        var generator = SystemRandomNumberGenerator()
        return String((0..<length).map { _ in alphabet.randomElement(using: &generator)! })
    }
}
