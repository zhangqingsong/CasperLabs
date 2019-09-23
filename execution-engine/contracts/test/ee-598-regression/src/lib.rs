#![no_std]

#[macro_use]
extern crate alloc;
extern crate contract_ffi;

use contract_ffi::contract_api;
use contract_ffi::contract_api::pointers::ContractPointer;
use contract_ffi::key::Key;
use contract_ffi::value::account::PurseId;
use contract_ffi::value::U512;

enum Error {
    GetPosURef = 1000,
}

fn purse_to_key(p: PurseId) -> Key {
    Key::URef(p.value())
}

fn get_pos_contract() -> ContractPointer {
    contract_api::get_pos().unwrap_or_else(|| contract_api::revert(Error::GetPosURef as u32))
}

const POS_BOND: &str = "bond";
const POS_UNBOND: &str = "unbond";

fn bond(pos: ContractPointer, amount: U512, source: PurseId) {
    contract_api::call_contract::<_, ()>(
        pos,
        &(POS_BOND, amount, source),
        &vec![purse_to_key(source)],
    );
}

fn unbond(pos: ContractPointer, amount: Option<U512>) {
    contract_api::call_contract::<_, ()>(pos, &(POS_UNBOND, amount), &vec![]);
}

#[no_mangle]
pub extern "C" fn call() {
    let pos_pointer = get_pos_contract();
    let amount: U512 = contract_api::get_arg(0);
    bond(pos_pointer.clone(), amount, contract_api::main_purse());
    unbond(pos_pointer, Some(amount + 1));
}