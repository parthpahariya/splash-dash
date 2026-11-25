import XCTest

final class GameplayUITests: XCTestCase {
  override func setUpWithError() throws {
    continueAfterFailure = false
    XCUIDevice.shared.orientation = .portrait
  }
  override func tearDownWithError() throws { XCUIDevice.shared.orientation = .portrait }

  @MainActor func testStartSteerJumpPauseAndBackground() throws {
    let app = XCUIApplication()
    app.launch()
    XCTAssertTrue(app.buttons["Set sail"].waitForExistence(timeout: 10))
    app.buttons["Set sail"].tap()
    XCTAssertFalse(app.buttons["Steer left"].exists)
    XCTAssertFalse(app.buttons["Steer right"].exists)
    XCTAssertFalse(app.buttons["Jump (2)"].exists)
    let water = app.otherElements["Four-lane waterway"]
    XCTAssertTrue(water.waitForExistence(timeout: 3))
    water.swipeLeft()
    water.swipeRight()
    water.swipeUp()
    XCTAssertTrue((water.value as? String)?.contains("Jump recharging") == true)
    app.buttons["Pause"].tap()
    XCTAssertTrue(app.buttons["Back to the water"].waitForExistence(timeout: 3))
    XCTAssertFalse(app.buttons["Fire cannon"].exists)
    app.buttons["Back to the water"].tap()
    app.buttons["Fire cannon"].press(forDuration: 0.5)
    XCUIDevice.shared.press(.home)
    app.activate()
    XCTAssertTrue(app.buttons["Back to the water"].waitForExistence(timeout: 5))
    let shot = XCTAttachment(screenshot: app.screenshot())
    shot.name = "Paused safely after backgrounding"
    shot.lifetime = .keepAlways
    add(shot)
  }
  @MainActor func testLandscapeAndHelp() throws {
    let app = XCUIApplication()
    app.launch()
    XCTAssertTrue(app.buttons["Set sail"].waitForExistence(timeout: 10))
    XCUIDevice.shared.orientation = .landscapeLeft
    XCTAssertTrue(app.buttons["Set sail"].isHittable)
    app.buttons["Set sail"].tap()
    XCTAssertTrue(app.buttons["Fire cannon"].isHittable)
    XCTAssertFalse(app.buttons["Steer right"].exists)
    app.buttons["Pause"].tap()
    XCTAssertTrue(app.buttons["Back to the water"].waitForExistence(timeout: 3))
    app.buttons["How to play"].tap()
    XCTAssertTrue(app.staticTexts["Captain’s handbook"].waitForExistence(timeout: 3))
    app.buttons["Done"].tap()
    XCTAssertTrue(app.buttons["Back to the water"].waitForExistence(timeout: 3))
    let shot = XCTAttachment(screenshot: app.screenshot())
    shot.name = "Landscape controls and pause"
    shot.lifetime = .keepAlways
    add(shot)
  }
}
